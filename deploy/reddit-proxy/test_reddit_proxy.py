import os
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
        with patch.object(req, "body", AsyncMock(return_value=b"")), \
                patch.object(proxy.app.state, "reddit", upstream, create=True):
            await proxy.reddit_request(req, "dummy-token")
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

    async def test_parallel_rejections_reuse_refreshed_token(self):
        import asyncio
        import time

        async def refresh():
            proxy._token = "new"
            proxy._token_expiry = time.monotonic() + 60
            return "new"

        with patch.object(proxy, "_token", "old"), patch.object(proxy, "_token_expiry", 0), \
                patch.object(proxy, "_token_lock", asyncio.Lock()), \
                patch.object(proxy, "refresh_token", AsyncMock(side_effect=refresh)) as mocked:
            tokens = await asyncio.gather(*(proxy.get_token("old") for _ in range(3)))
            self.assertEqual(tokens, ["new"] * 3)
            mocked.assert_awaited_once()


if __name__ == "__main__":
    unittest.main()
