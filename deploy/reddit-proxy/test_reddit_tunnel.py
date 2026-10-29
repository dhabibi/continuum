"""Local wire tests; TLS certificates are temporary test-only OpenSSL output."""

import asyncio
from contextlib import redirect_stderr, redirect_stdout
from dataclasses import replace
import io
import re
from pathlib import Path
import socket
import ssl
import subprocess
import tempfile
import unittest
from unittest.mock import AsyncMock, patch

import reddit_tunnel as tunnel


PUBLIC_IP = "93.184.216.34"


def answer(address=PUBLIC_IP):
    family = socket.AF_INET6 if ":" in address else socket.AF_INET
    return (family, socket.SOCK_STREAM, socket.IPPROTO_TCP, "", (address, 443))


class PolicyTests(unittest.TestCase):
    def test_reddit_domains_and_exact_upload_hosts(self):
        for host in ("reddit.com", "www.reddit.com", "oauth.reddit.com", "redd.it", "i.redd.it",
                     "v.redd.it", "preview.redd.it", "redditmedia.com", "styles.redditmedia.com",
                     "redditstatic.com", "www.redditstatic.com", "redditmail.com", "click.redditmail.com",
                     "support.reddithelp.com", "www.redditinc.com", "www.redditstatus.com", "matrix.redditspace.com",
                     "reddit.app.link", "reddit-uploaded-media.s3-accelerate.amazonaws.com",
                     "reddit-uploaded-video.s3-accelerate.amazonaws.com"):
            with self.subTest(host=host):
                self.assertEqual(tunnel.parse_connect(f"CONNECT {host}:443 HTTP/1.1\r\nHost: {host}\r\n\r\n".encode()), host)
        self.assertEqual(tunnel.parse_connect(b"CONNECT WWW.REDDIT.COM:443 HTTP/1.0\r\n\r\n"), "www.reddit.com")

    def test_boundary_spoofs_credentials_addresses_and_cleartext_are_rejected(self):
        for authority in ("reddit.com.evil:443", "evilreddit.com:443", "reddit.com:80", "reddit.com:0443",
                          "reddit.com:443/path", "reddit.com.:443", ".reddit.com:443", "a..reddit.com:443",
                          "a_1.reddit.com:443", "user:password@reddit.com:443", "reddit.com%00.evil:443",
                          "localhost:443", "127.0.0.1:443", "[::1]:443", "2130706433:443",
                          "https://reddit.com:443", "evil.reddit.app.link:443", "app.link:443",
                          "evil.s3-accelerate.amazonaws.com:443",
                          "evil.reddit-uploaded-media.s3-accelerate.amazonaws.com:443"):
            with self.subTest(authority=authority):
                with self.assertRaises(tunnel.ProxyError):
                    tunnel.parse_connect(f"CONNECT {authority} HTTP/1.1\r\n\r\n".encode())

    def test_request_framing_and_header_rejections(self):
        for header in (b"GET http://reddit.com/ HTTP/1.1\r\n\r\n",
                       b"CONNECT reddit.com:443 HTTP/2\r\n\r\n",
                       b"CONNECT  reddit.com:443 HTTP/1.1\r\n\r\n",
                       b"CONNECT reddit.com:443 HTTP/1.1\r\n invalid: x\r\n\r\n",
                       b"CONNECT reddit.com:443 HTTP/1.1\r\nX: x\nx\r\n\r\n",
                       b"CONNECT reddit.com:443 HTTP/1.1\r\nX: \x00\r\n\r\n",
                       b"CONNECT reddit.com:443 HTTP/1.1\r\nContent-Length: 4\r\n\r\n",
                       b"CONNECT reddit.com:443 HTTP/1.1\r\nTransfer-Encoding: chunked\r\n\r\n"):
            with self.subTest(header=header):
                with self.assertRaises(tunnel.ProxyError):
                    tunnel.parse_connect(header)

    def test_android_and_server_allowlists_match(self):
        selector = (Path(__file__).resolve().parents[2] / "app/src/main/java/ml/docilealligator/"
                    "infinityforreddit/network/RedditProxySelector.java").read_text()
        for java_name, expected in (("DOMAIN_SUFFIXES", tunnel.DOMAIN_ROOTS),
                                    ("EXACT_HOSTS", tunnel.EXACT_HOSTS)):
            match = re.search(java_name + r" = .*?Arrays\.asList\((.*?)\)\)", selector, re.S)
            self.assertIsNotNone(match)
            self.assertEqual(set(re.findall(r'"([^"\n]+)"', match.group(1))), set(expected))

    def test_non_public_ip_destinations_are_rejected(self):
        for address in ("127.0.0.1", "10.0.0.1", "172.16.0.1", "192.168.0.1", "169.254.169.254",
                        "100.100.100.100", "0.0.0.0", "224.0.0.1", "255.255.255.255", "192.0.2.1",
                        "::1", "::", "fe80::1", "fc00::1", "ff02::1", "2001:db8::1",
                        "::ffff:127.0.0.1", "::ffff:8.8.8.8", "not-an-ip"):
            with self.subTest(address=address):
                self.assertFalse(tunnel.public_address(address))
        self.assertTrue(tunnel.public_address(PUBLIC_IP))
        self.assertTrue(tunnel.public_address("2606:4700:4700::1111"))


class TunnelTests(unittest.IsolatedAsyncioTestCase):
    @classmethod
    def setUpClass(cls):
        cls.certs = tempfile.TemporaryDirectory(prefix="reddit-tunnel-test-")
        cls.cert = Path(cls.certs.name) / "cert.pem"
        cls.key = Path(cls.certs.name) / "key.pem"
        subprocess.run([
            "openssl", "req", "-x509", "-newkey", "rsa:2048", "-nodes", "-days", "1",
            "-subj", "/CN=www.reddit.com", "-addext", "subjectAltName=DNS:www.reddit.com",
            "-keyout", str(cls.key), "-out", str(cls.cert),
        ], check=True, capture_output=True, timeout=10)
        cls.server_tls = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        cls.server_tls.load_cert_chain(cls.cert, cls.key)
        cls.client_tls = ssl.create_default_context(cafile=str(cls.cert))

    @classmethod
    def tearDownClass(cls):
        cls.certs.cleanup()

    async def asyncSetUp(self):
        self.proxy = tunnel.RedditTunnel()
        self.servers = []
        self.writers = []
        self.origin_tasks = set()
        self.real_open = asyncio.open_connection
        await self.proxy.start(port=0)
        self.proxy_port = self.proxy.server.sockets[0].getsockname()[1]

    async def asyncTearDown(self):
        await self.proxy.stop()
        for writer in self.writers:
            writer.close()
        await asyncio.gather(*(self.proxy.close_writer(writer) for writer in self.writers))
        for server in self.servers:
            server.close()
            await server.wait_closed()
        for task in self.origin_tasks:
            task.cancel()
        await asyncio.gather(*self.origin_tasks, return_exceptions=True)

    async def client(self, request=None):
        reader, writer = await self.real_open("127.0.0.1", self.proxy_port)
        self.writers.append(writer)
        if request is not None:
            writer.write(request)
            await writer.drain()
        return reader, writer

    async def origin(self, handler, tls=False):
        async def run(reader, writer):
            self.origin_tasks.add(asyncio.current_task())
            try:
                await handler(reader, writer)
            finally:
                await self.proxy.close_writer(writer)
                self.origin_tasks.discard(asyncio.current_task())
        server = await asyncio.start_server(run, "127.0.0.1", 0, ssl=self.server_tls if tls else None)
        self.servers.append(server)
        return server.sockets[0].getsockname()[1]

    def local_dial(self, port):
        # Only the final numeric dial is redirected to our local test origin.
        # Production hostname parsing, DNS validation and CONNECT framing still run.
        async def dial(address, destination_port, **kwargs):
            self.assertEqual(address, PUBLIC_IP)
            self.assertEqual(destination_port, 443)
            return await self.real_open("127.0.0.1", port, **kwargs)
        return patch.object(tunnel.asyncio, "open_connection", side_effect=dial)

    async def connect(self):
        reader, writer = await self.client(b"CONNECT www.reddit.com:443 HTTP/1.1\r\nHost: www.reddit.com\r\n\r\n")
        response = await asyncio.wait_for(reader.readuntil(b"\r\n\r\n"), 2)
        self.assertEqual(response, b"HTTP/1.1 200 Connection Established\r\n\r\n")
        return reader, writer

    async def test_real_connect_tls_certificate_headers_and_large_response(self):
        request = b"GET /private?token=test-only HTTP/1.1\r\nHost: www.reddit.com\r\nAuthorization: test-only\r\n\r\n"
        body = bytes(range(256)) * 4096
        received = asyncio.get_running_loop().create_future()

        async def serve(reader, writer):
            received.set_result(await reader.readuntil(b"\r\n\r\n"))
            writer.write(b"HTTP/1.1 206 Partial Content\r\nContent-Length: " + str(len(body)).encode() + b"\r\n\r\n")
            await writer.drain()
            for offset in range(0, len(body), 32768):
                writer.write(body[offset:offset + 32768])
                await writer.drain()

        port = await self.origin(serve, tls=True)
        loop = asyncio.get_running_loop()
        with patch.object(loop, "getaddrinfo", AsyncMock(return_value=[answer()])) as lookup, self.local_dial(port), \
                redirect_stdout(io.StringIO()) as output, redirect_stderr(io.StringIO()) as errors:
            reader, writer = await self.connect()
            await writer.start_tls(self.client_tls, server_hostname="www.reddit.com")
            # Verification is enabled: the origin's certificate crosses unchanged.
            self.assertEqual(writer.get_extra_info("ssl_object").getpeercert()["subjectAltName"],
                             (("DNS", "www.reddit.com"),))
            writer.write(request)
            await writer.drain()
            header = await asyncio.wait_for(reader.readuntil(b"\r\n\r\n"), 2)
            self.assertTrue(header.startswith(b"HTTP/1.1 206"))
            self.assertEqual(await asyncio.wait_for(reader.readexactly(len(body)), 2), body)
            self.assertEqual(await received, request)
            lookup.assert_awaited_once()
            self.assertEqual(output.getvalue(), "")
            self.assertEqual(errors.getvalue(), "")

    async def test_half_close_and_bytes_pipelined_after_connect_header(self):
        async def serve(reader, writer):
            data = await reader.read()
            writer.write(b"after-eof:" + data)
            await writer.drain()

        port = await self.origin(serve)
        with patch.object(asyncio.get_running_loop(), "getaddrinfo", AsyncMock(return_value=[answer()])), self.local_dial(port):
            reader, writer = await self.client(b"CONNECT www.reddit.com:443 HTTP/1.1\r\n\r\npipelined")
            self.assertIn(b"200 Connection Established", await reader.readuntil(b"\r\n\r\n"))
            writer.write_eof()
            self.assertEqual(await asyncio.wait_for(reader.read(), 2), b"after-eof:pipelined")

    async def test_forbidden_hosts_methods_and_dns_never_dial(self):
        with patch.object(tunnel.asyncio, "open_connection", AsyncMock()) as dial:
            for request, status in ((b"CONNECT reddit.com.evil:443 HTTP/1.1\r\n\r\n", 403),
                                    (b"CONNECT reddit.com:80 HTTP/1.1\r\n\r\n", 403),
                                    (b"GET http://reddit.com/ HTTP/1.1\r\n\r\n", 405)):
                reader, _ = await self.client(request)
                self.assertTrue((await asyncio.wait_for(reader.read(), 1)).startswith(f"HTTP/1.1 {status}".encode()))
            for addresses in ([answer("127.0.0.1")], [answer(), answer("10.0.0.1")],
                              [answer("::ffff:127.0.0.1")]):
                with patch.object(asyncio.get_running_loop(), "getaddrinfo", AsyncMock(return_value=addresses)):
                    reader, _ = await self.client(b"CONNECT reddit.com:443 HTTP/1.1\r\n\r\n")
                    self.assertTrue((await asyncio.wait_for(reader.read(), 1)).startswith(b"HTTP/1.1 403"))
            dial.assert_not_awaited()

    async def test_dial_uses_validated_numeric_result_without_second_resolution(self):
        lookup = AsyncMock(side_effect=[[answer()], [answer("127.0.0.1")]])
        expected = (object(), object())
        with patch.object(asyncio.get_running_loop(), "getaddrinfo", lookup), \
                patch.object(tunnel.asyncio, "open_connection", AsyncMock(return_value=expected)) as dial:
            self.assertEqual(await self.proxy.connect("www.reddit.com"), expected)
            lookup.assert_awaited_once()
            self.assertEqual(dial.await_args.args, (PUBLIC_IP, 443))

    async def test_public_address_fallback_and_connect_failure(self):
        with patch.object(asyncio.get_running_loop(), "getaddrinfo", AsyncMock(return_value=[answer(), answer("8.8.8.8")])), \
                patch.object(tunnel.asyncio, "open_connection", AsyncMock(side_effect=[OSError(), (None, None)])) as dial:
            self.assertEqual(await self.proxy.connect("reddit.com"), (None, None))
            self.assertEqual(dial.await_count, 2)
        with patch.object(self.proxy, "connect", AsyncMock(side_effect=OSError("private-details"))):
            reader, _ = await self.client(b"CONNECT reddit.com:443 HTTP/1.1\r\n\r\n")
            self.assertEqual(await asyncio.wait_for(reader.read(), 1),
                             b"HTTP/1.1 502 Bad Gateway\r\nConnection: close\r\nContent-Length: 0\r\n\r\n")

    async def test_header_size_timeout_and_client_capacity_recover(self):
        self.proxy.limits = replace(self.proxy.limits, max_clients=2, max_header_bytes=256, header_timeout=0.1)
        # The reader limit belongs to the listening server, so restart with these limits.
        await self.proxy.stop()
        await self.proxy.start(port=0)
        self.proxy_port = self.proxy.server.sockets[0].getsockname()[1]
        first, first_writer = await self.client()
        second, second_writer = await self.client()
        third, _ = await self.client()
        self.assertTrue((await asyncio.wait_for(third.read(), 1)).startswith(b"HTTP/1.1 503"))
        self.assertLessEqual(len(self.proxy.connections), 2)
        self.assertTrue((await asyncio.wait_for(first.read(), 1)).startswith(b"HTTP/1.1 408"))
        self.assertTrue((await asyncio.wait_for(second.read(), 1)).startswith(b"HTTP/1.1 408"))
        first_writer.close()
        second_writer.close()
        await asyncio.sleep(0.01)
        large, _ = await self.client(b"CONNECT reddit.com:443 HTTP/1.1\r\nX: " + b"a" * 256)
        self.assertTrue((await asyncio.wait_for(large.read(), 1)).startswith(b"HTTP/1.1 431"))
        await asyncio.sleep(0.01)
        available, _ = await self.client(b"GET / HTTP/1.1\r\n\r\n")
        self.assertTrue((await asyncio.wait_for(available.read(), 1)).startswith(b"HTTP/1.1 405"))

    async def test_timed_out_dns_work_remains_bounded_until_lookup_finishes(self):
        self.proxy.limits = replace(self.proxy.limits, max_dns_queries=2)
        release = asyncio.Event()

        async def lookup(*args, **kwargs):
            await release.wait()
            return [answer()]

        with patch.object(asyncio.get_running_loop(), "getaddrinfo", side_effect=lookup) as resolver:
            for _ in range(2):
                with self.assertRaises(TimeoutError):
                    await asyncio.wait_for(self.proxy.resolve("reddit.com"), 0.02)
            self.assertEqual(len(self.proxy.dns_queries), 2)
            for _ in range(20):
                with self.assertRaises(tunnel.ProxyError) as error:
                    await self.proxy.resolve("reddit.com")
                self.assertEqual(error.exception.status, 503)
            self.assertEqual(resolver.await_count, 2)
            release.set()
            await asyncio.gather(*self.proxy.dns_queries)
            await asyncio.sleep(0)
            self.assertEqual(len(self.proxy.dns_queries), 0)
            self.assertEqual(await self.proxy.resolve("reddit.com"), [(socket.AF_INET, PUBLIC_IP)])

    async def test_connect_timeout_returns_gateway_timeout(self):
        self.proxy.limits = replace(self.proxy.limits, connect_timeout=0.03)

        async def stalled(host):
            await asyncio.Event().wait()

        with patch.object(self.proxy, "connect", side_effect=stalled):
            reader, _ = await self.client(b"CONNECT reddit.com:443 HTTP/1.1\r\n\r\n")
            self.assertTrue((await asyncio.wait_for(reader.read(), 1)).startswith(b"HTTP/1.1 504"))

    async def test_idle_tunnel_closes_and_downstream_activity_resets_idle_clock(self):
        self.proxy.limits = replace(self.proxy.limits, idle_timeout=0.08)

        async def quiet(reader, writer):
            await reader.read()

        port = await self.origin(quiet)
        with patch.object(asyncio.get_running_loop(), "getaddrinfo", AsyncMock(return_value=[answer()])), self.local_dial(port):
            reader, _ = await self.connect()
            self.assertEqual(await asyncio.wait_for(reader.read(), 1), b"")

        async def active(reader, writer):
            for _ in range(10):
                writer.write(b"x")
                await writer.drain()
                await asyncio.sleep(0.02)

        port = await self.origin(active)
        with patch.object(asyncio.get_running_loop(), "getaddrinfo", AsyncMock(return_value=[answer()])), self.local_dial(port):
            reader, _ = await self.connect()
            self.assertEqual(await asyncio.wait_for(reader.read(), 1), b"x" * 10)

    async def test_lifetime_and_shutdown_close_live_tunnels(self):
        self.proxy.limits = replace(self.proxy.limits, lifetime=0.08)

        async def active(reader, writer):
            try:
                while True:
                    writer.write(b"x")
                    await writer.drain()
                    await asyncio.sleep(0.01)
            except ConnectionError:
                pass

        port = await self.origin(active)
        with patch.object(asyncio.get_running_loop(), "getaddrinfo", AsyncMock(return_value=[answer()])), self.local_dial(port):
            reader, _ = await self.connect()
            data = await asyncio.wait_for(reader.read(), 1)
            self.assertGreater(len(data), 0)
            self.assertLess(len(data), 30)
            self.proxy.limits = replace(self.proxy.limits, lifetime=3600)
            reader, _ = await self.connect()
            await asyncio.wait_for(self.proxy.stop(), 2)
            await asyncio.wait_for(reader.read(), 1)
            self.assertTrue(all(task.done() for task in self.proxy.connections))


if __name__ == "__main__":
    unittest.main()
