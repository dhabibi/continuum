"""Private anonymous Reddit API and media proxy for Continuum."""

import asyncio
import base64
import gzip
import logging
import os
import re
import time
import uuid
from contextlib import asynccontextmanager
from dataclasses import dataclass, field
from email.utils import parsedate_to_datetime
from typing import Any
from urllib.parse import urljoin, urlsplit

import httpx
from curl_cffi.requests import AsyncSession
from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse, Response, StreamingResponse
from starlette.background import BackgroundTask

REDDIT_ANDROID_OAUTH_CLIENT_ID = "ohXpoqrZYub1kg"
TOKEN_URL = "https://www.reddit.com/auth/v2/oauth/access-token/loid"
API_BASE = "https://oauth.reddit.com"
PUBLIC_BASE = os.environ["PUBLIC_BASE"].rstrip("/")
APP_VERSION = os.getenv("REDDIT_APP_VERSION", "Version 2024.47.0/Build 2029755")
ANDROID_VERSION = os.getenv("ANDROID_VERSION", "14")
UPSTREAM_PROXY = os.getenv("UPSTREAM_PROXY") or None
REDDIT_TLS_IMPERSONATE = os.getenv("REDDIT_TLS_IMPERSONATE", "chrome_android")
SESSION_RECOVERY_COOLDOWN = 60
DEVICE_ID = os.getenv("DEVICE_ID", str(uuid.uuid4()))
USER_AGENT = f"Reddit/{APP_VERSION}/Android {ANDROID_VERSION}"
LOGGER = logging.getLogger("reddit_proxy")

EXACT_MEDIA_HOSTS = {
    "i.redd.it", "v.redd.it", "preview.redd.it", "external-preview.redd.it",
    "packaged-media.redd.it", "redditmedia.com", "redditstatic.com",
}
MEDIA_DOMAIN_SUFFIXES = (".redditmedia.com", ".redditstatic.com")
HOST_RE = re.compile(r"[a-z0-9]+(?:[.-][a-z0-9]+)*", re.IGNORECASE)
ABSOLUTE_URL_RE = re.compile(r'''https?://[A-Za-z0-9._-]+(?::[0-9]+)?/[^\s"'<>\\]+''')
MANIFEST_CONTENT_TYPES = (
    "application/dash+xml", "application/vnd.apple.mpegurl",
    "application/x-mpegurl", "audio/mpegurl",
)


def is_reddit_media_host(host: str) -> bool:
    host = host.lower()
    return bool(HOST_RE.fullmatch(host)) and (
        host in EXACT_MEDIA_HOSTS or host.endswith(MEDIA_DOMAIN_SUFFIXES)
    )


@asynccontextmanager
async def lifespan(app: FastAPI):
    app.state.reddit = RedditSessions()
    app.state.media = httpx.AsyncClient(
        proxy=UPSTREAM_PROXY, follow_redirects=False,
        timeout=httpx.Timeout(connect=20, read=90, write=30, pool=30),
    )
    try:
        yield
    finally:
        try:
            await app.state.reddit.close()
        finally:
            await app.state.media.aclose()


app = FastAPI(docs_url=None, redoc_url=None, openapi_url=None, lifespan=lifespan)


def device_headers() -> dict[str, str]:
    return {
        "User-Agent": USER_AGENT,
        "x-reddit-retry": "algo=no-retries",
        "x-reddit-compression": "1",
        "x-reddit-qos": "50.000",
        "x-reddit-media-codecs": "available-codecs=video/avc, video/hevc, video/x-vnd.on2.vp9",
        "Content-Type": "application/json; charset=UTF-8",
        "client-vendor-id": DEVICE_ID,
        "X-Reddit-Device-Id": DEVICE_ID,
    }


def create_reddit_client():
    return AsyncSession(
        impersonate=REDDIT_TLS_IMPERSONATE, default_headers=False,
        proxy=UPSTREAM_PROXY, timeout=30,
    )


@dataclass(frozen=True)
class RedditAuth:
    token: str
    expiry: float
    headers: dict[str, str]


class RedditAuthenticationError(RuntimeError):
    def __init__(self, response):
        super().__init__(f"Reddit token request failed: HTTP {response.status_code}")
        self.rate_limited = is_rate_limited(response)


@dataclass(eq=False)
class RedditSession:
    client: Any
    auth: RedditAuth | None = None
    users: int = 0
    retired: bool = False
    idle: asyncio.Event = field(default_factory=asyncio.Event)
    close_task: asyncio.Task | None = None

    def __post_init__(self):
        self.idle.set()


class RedditSessions:
    """Keep authentication and in-flight requests attached to their client generation."""

    def __init__(self, client_factory=create_reddit_client, clock=time.monotonic):
        self._factory = client_factory
        self._clock = clock
        self._current = RedditSession(client_factory())
        self._sessions = {self._current}
        self._lock = asyncio.Lock()
        self._next_recovery = 0.0
        self._closed = False

    @asynccontextmanager
    async def borrow(self):
        if self._closed:
            raise RuntimeError("Reddit sessions are closed")
        session = self._current
        session.users += 1
        session.idle.clear()
        try:
            yield session
        finally:
            session.users -= 1
            if not session.users:
                session.idle.set()
                if session.retired:
                    await self._close_session(session)

    async def _close_session(self, session):
        async def close():
            try:
                await session.client.close()
            finally:
                self._sessions.discard(session)

        if session.close_task is None:
            session.close_task = asyncio.create_task(close())
        await asyncio.shield(session.close_task)

    async def close(self):
        async with self._lock:
            self._closed = True
            sessions = list(self._sessions)
            for session in sessions:
                session.retired = True
        for session in sessions:
            await session.idle.wait()
            await self._close_session(session)

    async def _authenticate(self, session):
        basic = base64.b64encode(f"{REDDIT_ANDROID_OAUTH_CLIENT_ID}:".encode()).decode()
        response = await session.client.post(
            TOKEN_URL,
            headers={**device_headers(), "Authorization": f"Basic {basic}"},
            json={"scopes": ["*", "email", "pii"]},
            allow_redirects=False,
        )
        if response.status_code // 100 != 2:
            raise RedditAuthenticationError(response)
        data = response.json()
        auth = RedditAuth(
            token=data["access_token"],
            expiry=self._clock() + max(1, int(data.get("expires_in", 3600)) - 120),
            headers={key: response.headers[key]
                     for key in ("x-reddit-loid", "x-reddit-session") if response.headers.get(key)},
        )
        session.auth = auth
        return auth

    async def get_auth(self, session, rejected=None):
        def usable():
            return (session.auth is not None and session.auth is not rejected
                    and self._clock() < session.auth.expiry)

        if usable():
            return session.auth
        async with self._lock:
            if usable():
                return session.auth
            return await self._authenticate(session)

    async def recover(self, rejected):
        async with self._lock:
            if self._closed:
                return False
            if rejected is not self._current:
                return True  # Another request has already replaced this generation.
            if self._clock() < self._next_recovery:
                return False
            candidate = None
            try:
                candidate = RedditSession(self._factory())
                self._sessions.add(candidate)
                await self._authenticate(candidate)
                self._current = candidate
                rejected.retired = True
                LOGGER.info("Reddit session recovery succeeded")
                return True
            except Exception:
                # Exceptions from curl may contain URLs or credentials.
                LOGGER.warning("Reddit session recovery failed")
                return False
            finally:
                # Failures and successful resets both have a full cooldown.
                self._next_recovery = self._clock() + SESSION_RECOVERY_COOLDOWN
                if candidate is not None and candidate is not self._current:
                    await self._close_session(candidate)

    async def request(self, request):
        async with self.borrow() as session:
            auth = await self.get_auth(session)
            upstream = await reddit_request(request, session.client, auth)
            if (request.method not in ("GET", "HEAD") or upstream.status_code not in (401, 403)
                    or is_rate_limited(upstream)):
                return upstream
            try:
                auth = await self.get_auth(session, rejected=auth)
                upstream = await reddit_request(request, session.client, auth)
            except Exception as error:
                if isinstance(error, RedditAuthenticationError) and error.rate_limited:
                    return upstream
                if not is_feed_security_block(request, upstream):
                    raise
                # A blocked client's token endpoint can fail too. Keep the original
                # feed response if rebuilding the session cannot recover it.
            if not is_feed_security_block(request, upstream) or not await self.recover(session):
                return upstream
        # One retry against the current generation; no recursive recovery.
        async with self.borrow() as session:
            return await reddit_request(request, session.client, await self.get_auth(session))


def is_rate_limited(response):
    if response.status_code == 429:
        return True
    if response.status_code < 400:
        return False
    try:
        if float(response.headers.get("x-ratelimit-remaining", "nan")) <= 0:
            return True
    except ValueError:
        pass
    retry_after = response.headers.get("retry-after", "")
    try:
        return float(retry_after) > 0
    except ValueError:
        try:
            return parsedate_to_datetime(retry_after).timestamp() > time.time()
        except (ValueError, TypeError, OverflowError):
            return False


def is_feed_security_block(request, response):
    if (request.method not in ("GET", "HEAD") or response.status_code != 403
            or is_rate_limited(response)):
        return False
    path = request.url.path.rstrip("/")
    if path.endswith(".json"):
        path = path[:-5]
    listing = re.fullmatch(
        r"(?:/(?:r/[^/]+|(?:user|u)/[^/]+/m/[^/]+))?"
        r"(?:/(?:best|hot|new|top|controversial|rising))?", path,
    ) or re.fullmatch(r"/(?:user|u)/[^/]+/submitted", path)
    if not listing or "text/html" not in response.headers.get("content-type", "").lower():
        return False
    # HEAD omits the block page body; the listing route and HTML status identify it.
    return request.method == "HEAD" or b"network security" in response.content.lower()


def rewrite_url(url: str) -> str:
    try:
        parsed = urlsplit(url)
        if (parsed.scheme not in ("http", "https") or not parsed.hostname
                or parsed.username or parsed.password or parsed.port not in (None, 80, 443)
                or not is_reddit_media_host(parsed.hostname)):
            return url
        rewritten = f"{PUBLIC_BASE}/media/{parsed.hostname}{parsed.path or '/'}"
        if parsed.query:
            rewritten += f"?{parsed.query}"
        if parsed.fragment:
            rewritten += f"#{parsed.fragment}"
        return rewritten
    except ValueError:
        return url


def rewrite_urls_in_text(text: str) -> str:
    return ABSOLUTE_URL_RE.sub(lambda match: rewrite_url(match.group(0)), text)


def rewrite_json(value: Any) -> Any:
    if isinstance(value, dict):
        # Continuum recognizes Reddit's markdown embeds by their original URL.
        # Its media interceptor proxies the eventual image/video request.
        return {key: item if key in ("body", "selftext") else rewrite_json(item)
                for key, item in value.items()}
    if isinstance(value, list):
        return [rewrite_json(item) for item in value]
    if isinstance(value, str):
        return rewrite_urls_in_text(value)
    return value


def rewrite_manifest(text: str, upstream_url: str) -> str:
    text = rewrite_urls_in_text(text)
    if text.lstrip().startswith("#EXTM3U"):
        # HLS may use root-relative URLs and URI attributes as well as absolute URLs.
        lines = []
        for line in text.splitlines(keepends=True):
            if line.strip() and not line.startswith("#"):
                line = line.replace(line.strip(), rewrite_url(urljoin(upstream_url, line.strip())), 1)
            else:
                line = re.sub(r'URI="([^"]+)"', lambda m: 'URI="' + rewrite_url(
                    urljoin(upstream_url, m.group(1))) + '"', line)
            lines.append(line)
        return "".join(lines)
    return text


def raw_target(request: Request) -> str:
    path = request.scope.get("raw_path", request.url.path.encode()).decode("ascii")
    query = request.scope.get("query_string", b"").decode("ascii")
    return path + (f"?{query}" if query else "")


def reddit_api_target(request: Request) -> str:
    path, separator, query = raw_target(request).partition("?")
    parts = path.split("/", 3)
    if len(parts) >= 3 and parts[1].lower() == "r":
        # Retrofit leaves '+' literal in path parameters. Reddit's anonymous OAuth
        # API redirects that combined feed to its homepage, but accepts '%2B'.
        # Encode only the community selector; query '+' and existing escapes stay intact.
        parts[2] = parts[2].replace("+", "%2B")
    return "/".join(parts) + separator + query


async def reddit_request(request: Request, client, auth: RedditAuth):
    headers = {
        **device_headers(), **auth.headers,
        "Authorization": f"Bearer {auth.token}",
        "Accept": request.headers.get("accept", "*/*"),
        "Cookie": "_options=%7B%22pref_quarantine_optin%22%3A%20true%2C%20"
                  "%22pref_gated_sr_optin%22%3A%20true%7D",
    }
    if request.headers.get("content-type"):
        headers["Content-Type"] = request.headers["content-type"]
    body = await request.body()
    return await client.request(
        request.method, API_BASE + reddit_api_target(request), headers=headers,
        data=body if body else None, allow_redirects=False,
    )


async def open_media(method: str, url: str, headers: dict[str, str]) -> httpx.Response:
    for _ in range(6):
        parsed = urlsplit(url)
        if (parsed.scheme != "https" or not parsed.hostname
                or parsed.username or parsed.password or parsed.port not in (None, 443)
                or not is_reddit_media_host(parsed.hostname)):
            raise ValueError("Forbidden media redirect")
        upstream = await app.state.media.send(
            app.state.media.build_request(method, url, headers=headers), stream=True,
        )
        if upstream.status_code not in (301, 302, 303, 307, 308):
            return upstream
        location = upstream.headers.get("location")
        await upstream.aclose()
        if not location:
            raise ValueError("Media redirect has no location")
        url = urljoin(str(upstream.url), location)
    raise ValueError("Too many media redirects")


@app.api_route("/media/{host}/{path:path}", methods=["GET", "HEAD"])
async def media_proxy(host: str, path: str, request: Request):
    if not is_reddit_media_host(host):
        return Response("Forbidden media host", status_code=403)
    target = raw_target(request)
    prefix = f"/media/{host}/"
    if not target.startswith(prefix):
        return Response("Invalid media path", status_code=400)
    upstream_url = f"https://{host}/{target[len(prefix):]}"
    headers = {
        "User-Agent": USER_AGENT, "Accept": request.headers.get("accept", "*/*"),
        "Accept-Encoding": "identity",
    }
    for key in ("range", "if-range", "if-none-match", "if-modified-since"):
        if request.headers.get(key):
            headers[key] = request.headers[key]
    try:
        upstream = await open_media(request.method, upstream_url, headers)
    except (httpx.HTTPError, ValueError):
        LOGGER.exception("Media request failed")
        return JSONResponse({"error": "media_proxy_failed"}, status_code=502)

    content_type = upstream.headers.get("content-type", "")
    response_headers = {
        key: upstream.headers[key]
        for key in ("content-type", "content-range", "accept-ranges", "cache-control",
                    "etag", "last-modified", "content-length", "content-encoding")
        if key in upstream.headers
    }
    if request.method == "HEAD" or upstream.status_code in (204, 304):
        await upstream.aclose()
        return Response(status_code=upstream.status_code, headers=response_headers)

    is_manifest = (upstream.url.path.lower().endswith((".mpd", ".m3u8"))
                   or any(kind in content_type.lower() for kind in MANIFEST_CONTENT_TYPES))
    if is_manifest and upstream.status_code == 200:
        try:
            body = await upstream.aread()
        finally:
            await upstream.aclose()
        text = rewrite_manifest(body.decode("utf-8", errors="replace"), str(upstream.url))
        for key in ("content-length", "content-encoding", "content-range"):
            response_headers.pop(key, None)
        return Response(text.encode(), status_code=upstream.status_code, headers=response_headers)

    async def stream_body():
        try:
            async for chunk in upstream.aiter_raw():
                yield chunk
        finally:
            await upstream.aclose()

    return StreamingResponse(
        stream_body(), status_code=upstream.status_code, headers=response_headers,
        background=BackgroundTask(upstream.aclose),
    )


@app.get("/healthz")
async def healthz():
    return {"ok": True}


def accepts_gzip(accept_encoding: str) -> bool:
    wildcard = False
    for item in accept_encoding.split(","):
        coding, *parameters = item.strip().lower().split(";")
        quality = 1.0
        for parameter in parameters:
            key, _, value = parameter.partition("=")
            if key.strip() == "q":
                try:
                    quality = float(value.strip())
                except ValueError:
                    quality = 0.0
        accepted = 0.0 < quality <= 1.0
        if coding.strip() == "gzip":
            return accepted
        if coding.strip() == "*":
            wildcard = accepted
    return wildcard


def api_json_response(content: Any, request: Request, status_code: int = 200,
                      headers: dict[str, str] | None = None) -> JSONResponse:
    """Compress listing JSON only; media streams and byte ranges retain their existing path."""
    response = JSONResponse(content, status_code=status_code, headers=headers)
    if len(response.body) < 1024:
        return response
    response.headers.add_vary_header("Accept-Encoding")
    if accepts_gzip(request.headers.get("accept-encoding", "")):
        compressed = gzip.compress(response.body, compresslevel=1, mtime=0)
        if len(compressed) < len(response.body):
            response.body = compressed
            response.headers["content-encoding"] = "gzip"
            response.headers["content-length"] = str(len(compressed))
    return response


@app.api_route("/{path:path}", methods=["GET", "HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"])
async def proxy(path: str, request: Request):
    try:
        upstream = await app.state.reddit.request(request)
        response_headers = {
            key: upstream.headers[key]
            for key in ("cache-control", "x-ratelimit-remaining", "x-ratelimit-reset",
                        "x-ratelimit-used", "retry-after")
            if upstream.headers.get(key)
        }
        content_type = upstream.headers.get("content-type", "")
        if "json" in content_type.lower() and request.method != "HEAD":
            return api_json_response(rewrite_json(upstream.json()), request,
                                     status_code=upstream.status_code, headers=response_headers)
        if content_type:
            response_headers["content-type"] = content_type
        return Response(upstream.content, status_code=upstream.status_code, headers=response_headers)
    except Exception:
        LOGGER.warning("Reddit API request failed")
        return JSONResponse({"error": "reddit_proxy_failed"}, status_code=502)
