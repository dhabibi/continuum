"""Private Reddit-only HTTP CONNECT relay. TLS stays between the app and Reddit."""

import asyncio
from dataclasses import dataclass
import ipaddress
import os
import re
import signal
import socket


# Keep this policy aligned with Android's Reddit connection routing.
DOMAIN_ROOTS = (
    "reddit.com", "redd.it", "redditmedia.com", "redditstatic.com", "redditmail.com",
    "reddithelp.com", "redditinc.com", "redditstatus.com", "redditspace.com",
)
EXACT_HOSTS = frozenset({
    "reddit.app.link",
    "reddit-uploaded-media.s3-accelerate.amazonaws.com",
    "reddit-uploaded-video.s3-accelerate.amazonaws.com",
})
DNS_LABEL = re.compile(r"[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\Z")
HEADER_NAME = re.compile(rb"[!#$%&'*+.^_`|~0-9A-Za-z-]+\Z")


@dataclass(frozen=True)
class Limits:
    max_clients: int = 64
    max_header_bytes: int = 8192
    max_dns_queries: int = 8
    header_timeout: float = 10
    connect_timeout: float = 12
    connect_attempt_timeout: float = 4
    idle_timeout: float = 120
    lifetime: float = 3600
    close_timeout: float = 1


class ProxyError(Exception):
    def __init__(self, status):
        self.status = status
        super().__init__(status)


def allowed_host(host):
    return (len(host) <= 253 and all(DNS_LABEL.fullmatch(label) for label in host.split("."))
            and (host in EXACT_HOSTS or any(host == root or host.endswith("." + root)
                                           for root in DOMAIN_ROOTS)))


def parse_connect(header):
    """Accept a strict authority-form CONNECT, without forwarding proxy headers."""
    try:
        lines = header[:-4].split(b"\r\n")
        method, authority, version = lines[0].decode("ascii").split(" ")
        if version not in ("HTTP/1.0", "HTTP/1.1"):
            raise ProxyError(400)
        if method != "CONNECT":
            raise ProxyError(405)
        host, port = authority.rsplit(":", 1)
        host = host.lower()
        if port != "443" or not allowed_host(host):
            raise ProxyError(403)
        for line in lines[1:]:
            name, value = line.split(b":", 1)
            if not HEADER_NAME.fullmatch(name) or any(byte < 32 and byte != 9 or byte == 127
                                                     for byte in value):
                raise ProxyError(400)
            if name.lower() == b"transfer-encoding":
                raise ProxyError(400)
            if name.lower() == b"content-length" and value.strip() != b"0":
                raise ProxyError(400)
        return host
    except (ValueError, UnicodeError):
        raise ProxyError(400) from None


def public_address(address):
    try:
        ip = ipaddress.ip_address(address)
    except ValueError:
        return False
    return (ip.is_global and not ip.is_multicast and not ip.is_reserved
            and not (isinstance(ip, ipaddress.IPv6Address) and ip.ipv4_mapped is not None))


def consume_exception(task):
    # Never allow asyncio's default exception handler to print connection details.
    if not task.cancelled():
        task.exception()


class RedditTunnel:
    def __init__(self, limits=Limits()):
        self.limits = limits
        self.connections = set()
        self.dns_queries = set()
        self.server = None

    async def start(self, host="127.0.0.1", port=8789):
        self.server = await asyncio.start_server(
            self.accept, host, port, limit=self.limits.max_header_bytes,
            backlog=self.limits.max_clients)
        return self.server

    def accept(self, reader, writer):
        # Do not queue a task per rejected socket under sustained overload.
        if len(self.connections) >= self.limits.max_clients:
            writer.write(b"HTTP/1.1 503 Service Unavailable\r\nConnection: close\r\nContent-Length: 0\r\n\r\n")
            writer.close()
            return
        task = asyncio.create_task(self.handle(reader, writer))
        self.connections.add(task)
        task.add_done_callback(self.connections.discard)
        task.add_done_callback(consume_exception)

    async def resolve(self, host):
        # Shield in-flight OS lookups from client timeout/cancellation. Their slots
        # remain occupied until the actual lookup ends, so executor work is bounded.
        if len(self.dns_queries) >= self.limits.max_dns_queries:
            raise ProxyError(503)
        query = asyncio.create_task(asyncio.get_running_loop().getaddrinfo(
            host, 443, family=socket.AF_UNSPEC, type=socket.SOCK_STREAM,
            proto=socket.IPPROTO_TCP))
        self.dns_queries.add(query)
        query.add_done_callback(self.dns_queries.discard)
        query.add_done_callback(consume_exception)
        answers = await asyncio.shield(query)
        addresses = []
        for family, _, _, _, sockaddr in answers:
            address = sockaddr[0]
            if family not in (socket.AF_INET, socket.AF_INET6) or not public_address(address):
                raise ProxyError(403)
            item = (family, address)
            if item not in addresses:
                addresses.append(item)
        if not addresses or len(addresses) > 16:
            raise ProxyError(502)
        return addresses

    async def connect(self, host):
        addresses = await self.resolve(host)
        for family, address in addresses:
            try:
                # Numeric address only: never resolve the untrusted hostname again.
                return await asyncio.wait_for(asyncio.open_connection(
                    address, 443, family=family, limit=self.limits.max_header_bytes),
                    self.limits.connect_attempt_timeout)
            except (OSError, TimeoutError):
                continue
        raise ProxyError(502)

    async def reply(self, writer, status):
        reason = {400: "Bad Request", 403: "Forbidden", 405: "Method Not Allowed",
                  408: "Request Timeout", 431: "Request Header Fields Too Large",
                  502: "Bad Gateway", 503: "Service Unavailable", 504: "Gateway Timeout"}[status]
        writer.write(f"HTTP/1.1 {status} {reason}\r\nConnection: close\r\nContent-Length: 0\r\n\r\n".encode("ascii"))
        await asyncio.wait_for(writer.drain(), self.limits.close_timeout)

    async def close_writer(self, writer):
        if writer is None:
            return
        writer.close()
        try:
            await asyncio.wait_for(writer.wait_closed(), self.limits.close_timeout)
        except (OSError, TimeoutError):
            writer.transport.abort()

    async def handle(self, reader, writer):
        upstream_writer = None
        established = False
        try:
            try:
                header = await asyncio.wait_for(reader.readuntil(b"\r\n\r\n"), self.limits.header_timeout)
            except TimeoutError:
                raise ProxyError(408) from None
            except asyncio.LimitOverrunError:
                raise ProxyError(431) from None
            except asyncio.IncompleteReadError:
                raise ProxyError(400) from None
            if len(header) > self.limits.max_header_bytes:
                raise ProxyError(431)
            host = parse_connect(header)
            upstream_reader, upstream_writer = await asyncio.wait_for(self.connect(host), self.limits.connect_timeout)
            writer.write(b"HTTP/1.1 200 Connection Established\r\n\r\n")
            await asyncio.wait_for(writer.drain(), self.limits.close_timeout)
            established = True
            await self.relay(reader, writer, upstream_reader, upstream_writer)
        except ProxyError as error:
            if not established:
                await self.reply(writer, error.status)
        except (OSError, TimeoutError) as error:
            if not established:
                await self.reply(writer, 504 if isinstance(error, TimeoutError) else 502)
        finally:
            await asyncio.gather(self.close_writer(writer), self.close_writer(upstream_writer))

    async def relay(self, client_reader, client_writer, upstream_reader, upstream_writer):
        loop = asyncio.get_running_loop()
        started = last_activity = loop.time()

        async def copy(reader, writer):
            nonlocal last_activity
            while data := await reader.read(64 * 1024):
                writer.write(data)
                await asyncio.wait_for(writer.drain(), self.limits.idle_timeout)
                last_activity = loop.time()
            # Keep the opposite direction alive for responses after a client EOF.
            if writer.can_write_eof():
                writer.write_eof()
                await asyncio.wait_for(writer.drain(), self.limits.idle_timeout)

        async def watchdog():
            while True:
                remaining = min(self.limits.idle_timeout - (loop.time() - last_activity),
                                self.limits.lifetime - (loop.time() - started))
                if remaining <= 0:
                    raise TimeoutError
                await asyncio.sleep(remaining)

        pumps = asyncio.gather(copy(client_reader, upstream_writer), copy(upstream_reader, client_writer))
        timer = asyncio.create_task(watchdog())
        try:
            done, _ = await asyncio.wait((pumps, timer), return_when=asyncio.FIRST_COMPLETED)
            for task in done:
                task.result()
        finally:
            pumps.cancel()
            timer.cancel()
            await asyncio.gather(pumps, timer, return_exceptions=True)

    async def stop(self):
        if self.server is not None:
            self.server.close()
        tasks = tuple(self.connections | self.dns_queries)
        for task in tasks:
            task.cancel()
        await asyncio.gather(*tasks, return_exceptions=True)
        if self.server is not None:
            await self.server.wait_closed()


async def main():
    tunnel = RedditTunnel()
    await tunnel.start(os.environ.get("TUNNEL_HOST", "0.0.0.0"), int(os.environ.get("TUNNEL_PORT", "8789")))
    stopped = asyncio.Event()
    loop = asyncio.get_running_loop()
    for sig in (signal.SIGTERM, signal.SIGINT):
        loop.add_signal_handler(sig, stopped.set)
    try:
        await stopped.wait()
    finally:
        await tunnel.stop()


if __name__ == "__main__":
    asyncio.run(main())
