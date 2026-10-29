# Private Reddit CONNECT tunnel

`reddit_tunnel.py` is an additional, independent forward proxy for Android HTTPS
connections, including authenticated requests, WebView, media and downloads. It
relays TCP after `CONNECT host:443`; TLS certificates, request headers, cookies,
URLs, response bytes and redirects remain between the Android client and Reddit.
It does not decrypt TLS, modify requests, acquire tokens, or log connections,
credentials, request URLs, or payloads. The existing anonymous API on 10443 stays
separate and continues to provide its existing API behavior.

The allowlist must match Android's Reddit routing policy:

- `reddit.com`, `redd.it`, `redditmedia.com`, `redditstatic.com`, `redditmail.com`,
  `reddithelp.com`, `redditinc.com`, `redditstatus.com`, `redditspace.com`,
  and subdomains of each, using DNS label boundaries.
- Exact host `reddit.app.link`.
- Exact hosts `reddit-uploaded-media.s3-accelerate.amazonaws.com` and
  `reddit-uploaded-video.s3-accelerate.amazonaws.com`, used by the app's media
  upload API. Other AWS tenants and other `app.link` hosts are excluded.

Ownership references: [Reddit corporate site](https://redditinc.com/) links the
[help center](https://support.reddithelp.com/); the
[status site](https://www.redditstatus.com/) identifies Reddit services.
[Certificate transparency for redditspace.com](https://radar.cloudflare.com/domains/domain/redditspace.com)
records the certificate organization as Reddit, Inc.

Only port 443 is accepted. Plain HTTP forward requests and CONNECT to port 80
fail closed. Upgrade legacy Reddit cleartext URLs to HTTPS in the app before
loading them. External destinations, including external redirects opened by
WebView, need their own client routing decision; this tunnel rejects them.

Every resolved IP must be globally routable, excluding reserved and multicast
addresses and IPv4-mapped IPv6. Mixed public/private answers are rejected. The
connection uses a validated numeric IP, never a second hostname lookup. Each
client gets a bounded header and connect deadline, 120 seconds of inactivity,
and a one-hour maximum tunnel lifetime. Activity in either direction resets the
idle deadline. Both directions use streaming reads and drain backpressure;
TCP half-close preserves a response after the request direction ends.

One process admits 64 concurrent clients, an 8 KiB CONNECT header per client,
and at most eight outstanding OS DNS lookups. A timed-out lookup holds its DNS
slot until it actually finishes. Excess connections/lookups fail with 503 rather
than accumulating a queue. Long-lived requests can retry through a new tunnel
after the lifetime limit. These server limits do not provide the app's fail-closed
behavior: Android must select this proxy for every Reddit request and must never
retry a failed Reddit connection directly.

Build from `deploy/reddit-proxy`:

```sh
docker build -f Dockerfile.tunnel -t reddit-tunnel .
```

Before installation, inspect host capacity, occupied ports, and existing Serve
routes. Run as the Hermes owner, with Docker enabled at boot and a private tailnet
ACL restricting the new port to authorized clients. Example using a free port:

```sh
docker run -d --name reddit-tunnel --restart unless-stopped \
  -p 127.0.0.1:8789:8789 \
  --read-only --cap-drop ALL --security-opt no-new-privileges \
  --memory 192m --cpus 1 --pids-limit 32 \
  --log-opt max-size=1m --log-opt max-file=2 reddit-tunnel
tailscale serve --bg --tcp=10444 tcp://127.0.0.1:8789
```

Use Tailscale TCP forwarding, not an HTTPS reverse proxy: the Android HTTP proxy
client sends CONNECT on the TCP connection. Never publish the Docker listener on
a public interface or enable Funnel. This service has no application-layer
credentials and relies on Tailscale and its ACL. Preserve the existing 10443
route. The image's healthcheck tests only the local listener, not Reddit access.
No secrets or endpoint names are baked into the image. `TUNNEL_HOST` and
`TUNNEL_PORT` can override the container listener for local tests; deployment
uses the defaults `0.0.0.0:8789` inside the loopback-published container.

Run the local wire tests with Python 3.12+ and the OpenSSL command available:

```sh
python -m unittest discover -s deploy/reddit-proxy -p 'test_reddit_tunnel.py' -v
```

The tests use a temporary self-signed Reddit-named TLS origin and redirect only
the final validated public-IP dial to loopback. The client verifies the origin's
certificate through CONNECT and transfers a 1 MiB response unchanged. Separate
wire tests cover policy/DNS refusals, numeric-IP dialing, pipelined bytes,
half-close, timeouts, client/DNS limits, idle activity, lifetime and shutdown.
Test TLS keys are temporary and removed when tests finish.

Before release, verify a real Reddit TLS request through the deployed TCP route
and the Android login, WebView and download paths with Tailscale connected. Then
disconnect Tailscale or stop only this tunnel and verify that Reddit requests fail
without a direct fallback. A desktop request is a server smoke check only:

```sh
curl --fail --max-time 20 --proxy http://YOUR_TAILNET_HOST:10444 \
  --head https://www.reddit.com/
```

A Reddit 403 demonstrates upstream reachability but does not establish working
login or feed access. Actual phone behavior and automatic recovery after reboot
must be observed separately; this document does not assert deployment or release.
