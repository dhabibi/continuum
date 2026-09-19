"""Private anonymous Reddit API and media proxy for Continuum."""

import asyncio
import base64
import logging
import os
import re
import time
import uuid
from contextlib import asynccontextmanager
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
    app.state.reddit = AsyncSession(
        impersonate="chrome_android", default_headers=False,
        proxy=UPSTREAM_PROXY, timeout=30,
    )
    app.state.media = httpx.AsyncClient(
        proxy=UPSTREAM_PROXY, follow_redirects=False,
        timeout=httpx.Timeout(connect=20, read=90, write=30, pool=30),
    )
    try:
        yield
    finally:
        await app.state.reddit.close()
        await app.state.media.aclose()


app = FastAPI(docs_url=None, redoc_url=None, openapi_url=None, lifespan=lifespan)
_token = ""
_token_expiry = 0.0
_extra_headers: dict[str, str] = {}
_token_lock = asyncio.Lock()


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


async def refresh_token() -> str:
    global _token, _token_expiry, _extra_headers
    basic = base64.b64encode(f"{REDDIT_ANDROID_OAUTH_CLIENT_ID}:".encode()).decode()
    response = await app.state.reddit.post(
        TOKEN_URL,
        headers={**device_headers(), "Authorization": f"Basic {basic}"},
        json={"scopes": ["*", "email", "pii"]},
        allow_redirects=False,
    )
    if response.status_code // 100 != 2:
        raise RuntimeError(f"Reddit token request failed: HTTP {response.status_code}")
    data = response.json()
    _token = data["access_token"]
    _token_expiry = time.monotonic() + max(1, int(data.get("expires_in", 3600)) - 120)
    _extra_headers = {
        key: response.headers[key]
        for key in ("x-reddit-loid", "x-reddit-session") if response.headers.get(key)
    }
    return _token


async def get_token(rejected_token: str | None = None) -> str:
    # Concurrent failures share one refresh instead of each creating a new token.
    def usable() -> bool:
        return bool(_token) and _token != rejected_token and time.monotonic() < _token_expiry

    if usable():
        return _token
    async with _token_lock:
        if usable():
            return _token
        return await refresh_token()


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


async def reddit_request(request: Request, token: str):
    headers = {
        **device_headers(), **_extra_headers,
        "Authorization": f"Bearer {token}",
        "Accept": request.headers.get("accept", "*/*"),
        "Cookie": "_options=%7B%22pref_quarantine_optin%22%3A%20true%2C%20"
                  "%22pref_gated_sr_optin%22%3A%20true%7D",
    }
    if request.headers.get("content-type"):
        headers["Content-Type"] = request.headers["content-type"]
    body = await request.body()
    return await app.state.reddit.request(
        request.method, API_BASE + raw_target(request), headers=headers,
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


@app.api_route("/{path:path}", methods=["GET", "HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"])
async def proxy(path: str, request: Request):
    try:
        token = await get_token()
        upstream = await reddit_request(request, token)
        if upstream.status_code in (401, 403, 429):
            upstream = await reddit_request(request, await get_token(rejected_token=token))
        response_headers = {
            key: upstream.headers[key]
            for key in ("cache-control", "x-ratelimit-remaining", "x-ratelimit-reset",
                        "x-ratelimit-used", "retry-after")
            if upstream.headers.get(key)
        }
        content_type = upstream.headers.get("content-type", "")
        if "json" in content_type.lower() and request.method != "HEAD":
            return JSONResponse(rewrite_json(upstream.json()), status_code=upstream.status_code,
                                headers=response_headers)
        if content_type:
            response_headers["content-type"] = content_type
        return Response(upstream.content, status_code=upstream.status_code, headers=response_headers)
    except Exception:
        LOGGER.exception("Reddit API request failed")
        return JSONResponse({"error": "reddit_proxy_failed"}, status_code=502)
