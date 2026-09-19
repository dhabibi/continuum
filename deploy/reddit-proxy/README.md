# Private Reddit proxy

The app patch adds **Settings → API Keys → Reddit API Base URL**. Enable overrides,
enter the Tailscale HTTPS endpoint, and leave the page to apply the existing app
restart. Select Anonymous browsing. The proxy uses an anonymous Android identity;
it does not log into a Reddit account or provide account actions. Third-party
media hosts keep their ordinary direct connections.

Build the app with the `Proxy APK` workflow in this fork, or run
`./gradlew :app:assembleDebug --no-daemon --max-workers=2` with JDK 25 and Android
SDK 37. Debug APKs install alongside the standard Continuum app.

Build the service from this directory:

```sh
docker build -t reddit-proxy .
```

Create an untracked `.env` with `PUBLIC_BASE=https://your-host.ts.net:10443` and a
stable UUID in `DEVICE_ID`. `UPSTREAM_PROXY` is optional if Reddit blocks the VPS.
Keep that file private. Check occupied ports and current Tailscale Serve routes
before installation. Example using a free loopback port:

```sh
docker run -d --name reddit-proxy --restart unless-stopped \
  -p 127.0.0.1:8788:8787 --env-file .env \
  --read-only --tmpfs /tmp:rw,noexec,nosuid,size=32m \
  --cap-drop ALL --security-opt no-new-privileges \
  --memory 384m --cpus 1 --pids-limit 96 \
  --log-opt max-size=5m --log-opt max-file=2 reddit-proxy
tailscale serve --bg --https=10443 http://127.0.0.1:8788
```

Use Serve within the tailnet. Do not enable Funnel for this unauthenticated
service. `/healthz` checks the process only; verify a real listing, image, video
manifest and media range response before claiming Reddit works. The supplied
Android token endpoint is unofficial and Reddit can change or reject it.

`python -m unittest test_reddit_proxy` checks host restrictions, URL rewriting,
manifest routing, redirect boundaries, and range/header forwarding. The service
keeps raw URL encoding and query strings, rewrites Reddit media in JSON and
manifests, and restricts media redirects to Reddit-owned CDN hosts.

App changes are based on Daniel's supplied patch. The runtime uses the supplied
proxy design, with client lifecycle cleanup, deduplicated token refresh, strict
media redirect validation and correct raw-response encoding added for deployment.
