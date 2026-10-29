# Current state

October 29, 2026: v14 is published at the existing private APK download endpoint.
APK/source commit: 39fdde8247ffe4f7feca6b4bc042c870f5f2a84c.
Signed CI passed: https://github.com/dhabibi/continuum/actions/runs/40941982455
Package org.cygnusx1.continuum.proxy; 8.3.1.8-vps.14-debug; versionCode 243.
Signing certificate: c0e2f382bef12fdc2d9ad06e20b80e4d596c79b584ee8fbb70fd5e4bf79787b7.
Install over the existing app with Tailscale connected; no uninstall/settings reset.
Private links and hashes: ../outputs/continuum/INSTALL-v14.txt and SHA256SUMS-v14.txt.
Endpoint configuration remains outside Git. V12/v13 downloads remain available.

## Verified behavior

GIF autoplay has its own default-on setting, independent of video autoplay.
Multi-image galleries stream Reddit MP4 renditions and preload an adjacent GIF.
A real Android test found a 0x0 video surface after swiping; the fix keeps the
surface drawable below the thumbnail and preserves its sizing before layout.
Original media/download URLs, zoom, privacy/Data Saving gates and cleanup remain.
Reddit HTTP(S) requests use the VPS across API, media, download, authentication,
redirect and internal WebView paths; these routes have no direct fallback.
Daniel clarified that other destinations may keep ordinary routing. No exit node added.
The HTTP proxy does not establish an arbitrary WebRTC UDP guarantee; no such
Reddit flow was observed. Do not describe this as device-wide/browser anonymity.

105 release regressions and 41 Python proxy/tunnel tests passed; signed CI passed.
ARM64 Android 14 / WebView 113: real native HTTP, WebView images, service workers,
redirects, tunnel refusal and recovery passed. GIF pixels visibly changed; a fresh
cache received 1,048,674 bytes for the next animation; swipe/pause released players.
Both APKs passed package/version/certificate/ZIP checks, server full-hash comparison,
and HTTPS range/type/size/byte checks. Physical-phone behavior/login remain unobserved.

## Services and next action

Anonymous proxy reddit-proxy:2026-10-29-recovery remains healthy (source 8191e11).
Natural overnight session recovery remains unobserved; no scheduled restart added.
Reddit tunnel reddit-tunnel:2026-10-29 is live, private TCP 10444 to loopback 8789,
192 MiB / 1 CPU / 32 PIDs, nonroot/read-only, unless-stopped, no access logs.
Live Reddit TLS image 200 and disallowed target 403 verified; automatic restart
observed (restart_count 1); Docker/Tailscale enabled at boot. Other Serve routes preserved.
Temporary emulator and forwarding connection removed. No hermes-gateway restart.
Next action: install v14 and check GIFs on the phone. Comments remain excluded;
prior Home/TikTok phone crash reports are not claimed resolved by this release.
Canonical intent.md owner draft and .serena/ remain uncommitted and preserved.
