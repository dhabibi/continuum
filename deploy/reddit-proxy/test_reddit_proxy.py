import os
import asyncio
import gzip
import json
import unittest
from unittest.mock import AsyncMock, patch

import httpx
from starlette.requests import Request

os.environ.setdefault("PUBLIC_BASE", "https://proxy.example:10443")
import reddit_proxy as proxy


class Body(httpx.AsyncByteStream):
    async def __aiter__(self):
        yield b"video bytes"


def request(path, headers=(), method="GET", query=b""):
    return Request({
        "type": "http", "method": method, "scheme": "https", "path": path,
        "raw_path": path.encode(), "query_string": query, "headers": list(headers),
        "server": ("proxy.example", 10443),
    })


class JsonCompressionTests(unittest.TestCase):
    def test_large_listing_round_trips_with_correct_headers(self):
        payload = {"posts": [{"title": "A photo", "url": "https://proxy.example/media/i.redd.it/a.jpg"}] * 100}
        response = proxy.api_json_response(payload, request("/r/pics/hot.json", [(b"accept-encoding", b"gzip")]),
                                           headers={"cache-control": "private", "vary": "Origin"})
        self.assertEqual(response.headers["content-encoding"], "gzip")
        self.assertEqual(json.loads(gzip.decompress(response.body)), payload)
        self.assertEqual(int(response.headers["content-length"]), len(response.body))
        self.assertLess(len(response.body), len(json.dumps(payload)) // 2)
        self.assertEqual(response.headers["cache-control"], "private")
        self.assertIn("Origin", response.headers["vary"])
        self.assertIn("Accept-Encoding", response.headers["vary"])

    def test_identity_and_explicit_gzip_rejection_remain_readable(self):
        payload = {"body": "content " * 500}
        for encoding in (b"identity", b"gzip;q=0, *;q=1", b"br"):
            with self.subTest(encoding=encoding):
                response = proxy.api_json_response(payload, request("/r/pics/hot.json", [(b"accept-encoding", encoding)]))
                self.assertNotIn("content-encoding", response.headers)
                self.assertEqual(json.loads(response.body), payload)
                self.assertIn("Accept-Encoding", response.headers["vary"])


class RewritingTests(unittest.TestCase):
    def test_signed_json_urls_keep_encoding_and_external_links(self):
        image = "https://preview.redd.it/a%20b.jpg?width=640&format=pjpg&s=abc%2Fxyz"
        result = proxy.rewrite_json({"media": [image], "text": "https://example.com/a"})
        self.assertEqual(result["media"][0], proxy.PUBLIC_BASE +
                         "/media/preview.redd.it/a%20b.jpg?width=640&format=pjpg&s=abc%2Fxyz")
        self.assertEqual(result["text"], "https://example.com/a")

    def test_markdown_embed_syntax_is_preserved_for_the_app_parser(self):
        source = {"body": "https://i.redd.it/embed.png", "url": "https://i.redd.it/embed.png"}
        result = proxy.rewrite_json(source)
        self.assertEqual(result["body"], source["body"])
        self.assertEqual(result["url"], proxy.PUBLIC_BASE + "/media/i.redd.it/embed.png")

    def test_local_subscription_feed_encodes_separators_without_changing_queries(self):
        req = request("/r/LocalLLaMA+MachineLearning/hot.json",
                      query=b"after=t3_abc&raw_json=1&q=machine+learning")
        self.assertEqual(proxy.reddit_api_target(req),
                         "/r/LocalLLaMA%2BMachineLearning/hot.json"
                         "?after=t3_abc&raw_json=1&q=machine+learning")

    def test_existing_escapes_single_subreddits_and_other_paths_stay_intact(self):
        for path in ("/r/LocalLLaMA%2BMachineLearning/new.json", "/r/LocalLLaMA/hot.json",
                     "/user/example/submitted.json", "/comments/id/a+b.json"):
            with self.subTest(path=path):
                self.assertEqual(proxy.reddit_api_target(request(path)), path)

    def test_host_injection_and_suffix_spoofs_are_rejected(self):
        for host in ("i.redd.it.evil.com", "localhost", "127.0.0.1", "evil@i.redd.it",
                     "evil%2fi.redd.it", "i.redd.it:443", "redditstatic.com.evil"):
            with self.subTest(host=host):
                self.assertFalse(proxy.is_reddit_media_host(host))
        self.assertTrue(proxy.is_reddit_media_host("styles.redditmedia.com"))

    def test_hls_relative_and_absolute_segments_stay_on_proxy(self):
        manifest = '#EXTM3U\n#EXT-X-MAP:URI="/asset/init.mp4"\n../DASH_720.mp4\nhttps://v.redd.it/id/audio.mp4\n'
        result = proxy.rewrite_manifest(manifest, "https://v.redd.it/id/hls/playlist.m3u8")
        for path in ("/asset/init.mp4", "/id/DASH_720.mp4", "/id/audio.mp4"):
            self.assertIn(proxy.PUBLIC_BASE + "/media/v.redd.it" + path, result)


class MediaTests(unittest.IsolatedAsyncioTestCase):
    async def test_api_request_uses_the_encoded_feed_target(self):
        req = request("/r/AskReddit+NoStupidQuestions/hot.json", query=b"limit=2&after=t3_next")
        upstream = AsyncMock()
        with patch.object(req, "body", AsyncMock(return_value=b"")):
            await proxy.reddit_request(req, upstream, proxy.RedditAuth("dummy-token", 60, {}))
        self.assertEqual(upstream.request.call_args.args[:2],
                         ("GET", "https://oauth.reddit.com/r/AskReddit%2BNoStupidQuestions/hot.json"
                          "?limit=2&after=t3_next"))

    async def test_encoded_path_range_and_stream_are_preserved(self):
        seen = []

        def upstream(req):
            seen.append(req)
            return httpx.Response(206, headers={
                "Content-Type": "video/mp4", "Content-Range": "bytes 0-10/100",
                "Content-Length": "11", "Accept-Ranges": "bytes",
            }, stream=Body())

        async with httpx.AsyncClient(transport=httpx.MockTransport(upstream)) as client:
            with patch.object(proxy.app.state, "media", client, create=True):
                response = await proxy.media_proxy("v.redd.it", "id/a%20b.mp4", request(
                    "/media/v.redd.it/id/a%20b.mp4", [(b"range", b"bytes=0-10")], query=b"s=a%2Fb"))
                self.assertEqual(response.status_code, 206)
                self.assertEqual(response.headers["content-range"], "bytes 0-10/100")
                self.assertEqual(b"".join([part async for part in response.body_iterator]), b"video bytes")
        self.assertEqual(seen[0].url.raw_path, b"/id/a%20b.mp4?s=a%2Fb")
        self.assertEqual(seen[0].headers["range"], "bytes=0-10")

    async def test_media_redirect_cannot_leave_reddit(self):
        seen = []

        def upstream(req):
            seen.append(req)
            return httpx.Response(302, headers={"Location": "http://127.0.0.1/private"})

        async with httpx.AsyncClient(transport=httpx.MockTransport(upstream)) as client:
            with patch.object(proxy.app.state, "media", client, create=True):
                with self.assertRaises(ValueError):
                    await proxy.open_media("GET", "https://v.redd.it/id/video.mp4", {})
        self.assertEqual(len(seen), 1)



class SessionRecoveryTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.clients = []
        self.calls = []
        self.now = 1000.0
        self.token_counts = {}
        self.factory_fail = False
        self.token_failure = None
        self.handler = lambda generation, req: self.blocked() if generation == 0 else httpx.Response(
            200, json={"data": {"children": []}}, headers={"x-ratelimit-remaining": "98"})

        class Client(httpx.AsyncClient):
            # Match curl_cffi's redirect and close interface while exercising real
            # HTTP request construction, headers and the ASGI response path.
            async def request(self, *args, allow_redirects=False, **kwargs):
                return await super().request(*args, follow_redirects=allow_redirects, **kwargs)

            async def post(self, url, **kwargs):
                return await self.request("POST", url, **kwargs)

            async def close(self):
                await self.aclose()

        def factory():
            if self.factory_fail:
                raise RuntimeError("private token and URL must not reach logs")
            generation = len(self.clients)

            async def transport(req):
                self.calls.append((generation, req))
                if req.url == proxy.TOKEN_URL:
                    count = self.token_counts.get(generation, 0) + 1
                    self.token_counts[generation] = count
                    if count > 1 and self.token_failure is not None:
                        return self.token_failure
                    return httpx.Response(200, json={"access_token": f"token-{generation}-{count}",
                                                     "expires_in": 3600}, headers={
                        "x-reddit-loid": f"loid-{generation}-{count}",
                        "x-reddit-session": f"session-{generation}-{count}",
                    })
                result = self.handler(generation, req)
                if hasattr(result, "__await__"):
                    return await result
                return result

            client = Client(transport=httpx.MockTransport(transport))
            self.clients.append(client)
            return client

        self.factory = factory
        self.manager = proxy.RedditSessions(factory, clock=lambda: self.now)
        self.state_patch = patch.object(proxy.app.state, "reddit", self.manager, create=True)
        self.state_patch.start()
        self.api = httpx.AsyncClient(transport=httpx.ASGITransport(app=proxy.app), base_url="https://proxy.test")

    async def asyncTearDown(self):
        await self.api.aclose()
        await self.manager.close()
        self.state_patch.stop()

    @staticmethod
    def blocked():
        return httpx.Response(403, text="<html>You've been blocked by network security.</html>",
                              headers={"content-type": "text/html", "retry-after": "0"})

    def feed_calls(self):
        return [(generation, req) for generation, req in self.calls if req.url != proxy.TOKEN_URL]

    async def test_block_refresh_reset_and_retry_preserve_request_and_auth_pairing(self):
        response = await self.api.get("/r/one+two/hot.json?after=t3_page&limit=2")
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.headers["x-ratelimit-remaining"], "98")
        self.assertEqual(self.token_counts, {0: 2, 1: 1})
        self.assertEqual([generation for generation, _ in self.feed_calls()], [0, 0, 1])
        self.assertTrue(self.clients[0].is_closed)
        for generation, req in self.feed_calls():
            token = req.headers["authorization"].removeprefix("Bearer token-")
            self.assertEqual(req.headers["x-reddit-loid"], "loid-" + token)
            self.assertEqual(req.headers["x-reddit-session"], "session-" + token)
            self.assertTrue(token.startswith(str(generation) + "-"))
            self.assertEqual(req.headers["x-reddit-device-id"], proxy.DEVICE_ID)
            self.assertEqual(req.url.raw_path, b"/r/one%2Btwo/hot.json?after=t3_page&limit=2")

    async def test_failed_refresh_of_blocked_session_still_recovers(self):
        original = self.manager._authenticate

        async def authenticate(session):
            if session is self.manager._current and session.auth:
                raise RuntimeError("token refresh failed")
            return await original(session)

        with patch.object(self.manager, "_authenticate", side_effect=authenticate):
            response = await self.api.get("/hot")
        self.assertEqual(response.status_code, 200)
        self.assertEqual(len(self.clients), 2)
        self.assertEqual([generation for generation, _ in self.feed_calls()], [0, 1])

    async def test_parallel_failures_share_reset_and_keep_inflight_client_alive(self):
        held_started, release_held = asyncio.Event(), asyncio.Event()
        both_failed, allow_failures = asyncio.Event(), asyncio.Event()
        failures = 0

        async def handle(generation, req):
            nonlocal failures
            if generation:
                return httpx.Response(200, json={"generation": generation})
            if req.url.path == "/r/held/new.json":
                held_started.set()
                await release_held.wait()
                self.assertFalse(self.clients[0].is_closed)
                return httpx.Response(200, json={"held": True})
            if req.headers["authorization"] == "Bearer token-0-1":
                failures += 1
                if failures == 2:
                    both_failed.set()
                await allow_failures.wait()
            return self.blocked()

        self.handler = handle
        held = asyncio.create_task(self.api.get("/r/held/new.json"))
        await asyncio.wait_for(held_started.wait(), 2)
        requests = [asyncio.create_task(self.api.get("/r/pics/hot.json")) for _ in range(2)]
        try:
            await asyncio.wait_for(both_failed.wait(), 2)
            allow_failures.set()
            results = await asyncio.wait_for(asyncio.gather(*requests), 2)
            self.assertEqual([r.status_code for r in results], [200, 200])
            self.assertEqual(len(self.clients), 2)
            self.assertEqual(self.token_counts, {0: 2, 1: 1})
            self.assertFalse(self.clients[0].is_closed)
        finally:
            allow_failures.set()
            release_held.set()
            await asyncio.gather(held, *requests)
        self.assertTrue(self.clients[0].is_closed)

    async def test_cooldown_after_reset_and_single_fresh_retry_when_still_blocked(self):
        self.handler = lambda generation, req: self.blocked()
        first = await self.api.get("/new")
        self.assertEqual(first.status_code, 403)
        self.assertEqual(first.headers["retry-after"], "0")
        self.assertEqual([generation for generation, _ in self.feed_calls()], [0, 0, 1])
        self.now += 59
        self.assertEqual((await self.api.get("/new")).status_code, 403)
        self.assertEqual(len(self.clients), 2)
        self.now += 1
        await self.api.get("/new")
        self.assertEqual(len(self.clients), 3)

    async def test_failed_client_creation_is_cooled_down_and_not_logged_with_details(self):
        self.factory_fail = True
        with self.assertLogs("reddit_proxy", level="WARNING") as logs:
            self.assertEqual((await self.api.get("/new")).status_code, 403)
        self.assertEqual(logs.output, ["WARNING:reddit_proxy:Reddit session recovery failed"])
        self.factory_fail = False
        self.now += 59
        self.assertEqual((await self.api.get("/new")).status_code, 403)
        self.assertEqual(len(self.clients), 1)
        self.now += 1
        self.assertEqual((await self.api.get("/new")).status_code, 200)

    async def test_failed_candidate_auth_closes_candidate_and_preserves_current(self):
        original = self.manager._authenticate

        async def authenticate(session):
            if session is not self.manager._current:
                raise RuntimeError("private credentials")
            return await original(session)

        with patch.object(self.manager, "_authenticate", side_effect=authenticate):
            self.assertEqual((await self.api.get("/new")).status_code, 403)
            self.assertEqual((await self.api.get("/new")).status_code, 403)
        self.assertEqual(len(self.clients), 2)
        self.assertFalse(self.clients[0].is_closed)
        self.assertTrue(self.clients[1].is_closed)
        self.now += 60
        self.assertEqual((await self.api.get("/new")).status_code, 200)

    async def test_comments_other_403s_rate_limits_and_writes_do_not_reset(self):
        cases = [
            ("GET", "/comments/id.json", self.blocked(), 2),
            ("GET", "/r/pics/comments/id/title.json", self.blocked(), 2),
            ("GET", "/user/name/comments.json", self.blocked(), 2),
            ("GET", "/r/pics/about.json", self.blocked(), 2),
            ("GET", "/r/pics/hot.json", httpx.Response(403, json={"error": 403}), 2),
            ("GET", "/r/pics/hot.json", httpx.Response(403, text="private subreddit",
                                                       headers={"content-type": "text/html"}), 2),
            ("GET", "/r/pics/hot.json", httpx.Response(429, json={"error": 429}, headers={
                "retry-after": "37", "x-ratelimit-remaining": "0", "x-ratelimit-reset": "37",
                "x-ratelimit-used": "100"}), 1),
            *[(method, "/r/pics/hot.json", self.blocked(), 1)
              for method in ("POST", "PUT", "PATCH", "DELETE", "OPTIONS")],
        ]
        for method, path, result, expected_calls in cases:
            with self.subTest(method=method, path=path, status=result.status_code):
                self.handler = lambda generation, req: result
                count = len(self.feed_calls())
                response = await self.api.request(method, path)
                self.assertEqual(response.status_code, result.status_code)
                self.assertEqual(len(self.feed_calls()) - count, expected_calls)
                self.assertEqual(len(self.clients), 1)
                if result.status_code == 429:
                    for key in ("retry-after", "x-ratelimit-remaining", "x-ratelimit-reset", "x-ratelimit-used"):
                        self.assertEqual(response.headers[key], result.headers[key])

    async def test_token_endpoint_rate_limits_preserve_feed_response_without_reset(self):
        failures = [httpx.Response(429, json={"error": "rate_limited"}),
                    httpx.Response(503, headers={"retry-after": "30"}),
                    httpx.Response(403, headers={"retry-after": "Thu, 01 Jan 2099 00:00:00 GMT"})]
        for failure in failures:
            with self.subTest(status=failure.status_code):
                self.token_failure = failure
                prior_tokens = self.token_counts.get(0, 0)
                prior_feeds = len(self.feed_calls())
                response = await self.api.get("/new")
                self.assertEqual(response.status_code, 403)
                self.assertEqual(response.text, self.blocked().text)
                self.assertEqual(self.token_counts[0] - prior_tokens, 2 if not prior_tokens else 1)
                self.assertEqual(len(self.feed_calls()) - prior_feeds, 1)
                self.assertEqual(len(self.clients), 1)

    async def test_explicit_403_backoff_is_not_refreshed_or_reset(self):
        for headers in ({"retry-after": "13"},
                        {"retry-after": "Thu, 01 Jan 2099 00:00:00 GMT"},
                        {"x-ratelimit-remaining": "0.0"}):
            with self.subTest(headers=headers):
                response = self.blocked()
                response.headers.update(headers)
                self.handler = lambda generation, req: response
                count = len(self.feed_calls())
                result = await self.api.get("/r/pics/hot.json")
                self.assertEqual(result.status_code, 403)
                self.assertEqual(len(self.feed_calls()) - count, 1)
                self.assertEqual(self.token_counts, {0: 1})
                self.assertEqual(len(self.clients), 1)
                for key, value in headers.items():
                    self.assertEqual(result.headers[key], value)

    async def test_explicit_backoff_after_token_refresh_does_not_reset(self):
        def handle(generation, req):
            response = self.blocked()
            if req.headers["authorization"] == "Bearer token-0-2":
                response.headers["retry-after"] = "30"
            return response

        self.handler = handle
        response = await self.api.get("/new")
        self.assertEqual(response.status_code, 403)
        self.assertEqual(response.headers["retry-after"], "30")
        self.assertEqual(self.token_counts, {0: 2})
        self.assertEqual(len(self.clients), 1)

    async def test_normal_token_refresh_succeeds_without_replacing_client(self):
        self.handler = lambda generation, req: httpx.Response(
            401 if req.headers["authorization"] == "Bearer token-0-1" else 200,
            json={"ok": True})
        self.assertEqual((await self.api.get("/new")).status_code, 200)
        self.assertEqual(self.token_counts, {0: 2})
        self.assertEqual(len(self.clients), 1)
        self.now += 3600
        self.assertEqual((await self.api.get("/new")).status_code, 200)
        self.assertEqual(self.token_counts, {0: 3})

    async def test_cancelled_recovery_closes_candidate_and_applies_cooldown(self):
        authenticating = asyncio.Event()
        original = self.manager._authenticate

        async def authenticate(session):
            if session is not self.manager._current:
                authenticating.set()
                await asyncio.Event().wait()
            return await original(session)

        with patch.object(self.manager, "_authenticate", side_effect=authenticate):
            pending = asyncio.create_task(self.api.get("/new"))
            await asyncio.wait_for(authenticating.wait(), 2)
            pending.cancel()
            with self.assertRaises(asyncio.CancelledError):
                await pending
        self.assertEqual(len(self.clients), 2)
        self.assertTrue(self.clients[1].is_closed)
        self.assertFalse(self.clients[0].is_closed)
        self.assertEqual((await self.api.get("/new")).status_code, 403)
        self.assertEqual(len(self.clients), 2)
        self.now += 60
        self.assertEqual((await self.api.get("/new")).status_code, 200)

    async def test_head_listing_can_recover_without_response_body(self):
        self.handler = lambda generation, req: httpx.Response(
            403 if generation == 0 else 200, headers={"content-type": "text/html"})
        response = await self.api.head("/r/pics/hot.json")
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.content, b"")
        self.assertEqual([req.method for _, req in self.feed_calls()], ["HEAD"] * 3)

    async def test_gzip_and_media_urls_survive_recovered_listing(self):
        payload = {"data": [{"url": "https://i.redd.it/a.jpg", "title": "example"}] * 100}
        self.handler = lambda generation, req: self.blocked() if generation == 0 else httpx.Response(200, json=payload)
        response = await self.api.get("/r/pics/hot.json", headers={"accept-encoding": "gzip"})
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.headers["content-encoding"], "gzip")
        self.assertEqual(response.json(), proxy.rewrite_json(payload))

    async def test_shutdown_waits_for_active_generation_then_closes_every_client(self):
        entered, release = asyncio.Event(), asyncio.Event()

        async def hold():
            async with self.manager.borrow() as session:
                entered.set()
                await release.wait()
                self.assertFalse(session.client.is_closed)

        held = asyncio.create_task(hold())
        await entered.wait()
        shutdown = asyncio.create_task(self.manager.close())
        await asyncio.sleep(0)
        self.assertFalse(shutdown.done())
        release.set()
        await asyncio.wait_for(asyncio.gather(held, shutdown), 2)
        self.assertTrue(all(client.is_closed for client in self.clients))
        self.assertFalse(self.manager._sessions)
        with self.assertRaisesRegex(RuntimeError, "closed"):
            async with self.manager.borrow():
                self.fail("borrowed after shutdown")


if __name__ == "__main__":
    unittest.main()
