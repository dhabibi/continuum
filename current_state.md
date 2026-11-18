# Current state

November 18, 2026. Continuum Proxy v17 is published at the private APK endpoint.
APK source and release main: fc734a7f74226ef111ec6ecd595ecd977e43b522.
Signed CI run: https://github.com/dhabibi/continuum/actions/runs/44257394274.
Package org.cygnusx1.continuum.proxy, version 8.3.1.8-vps.17-debug, versionCode 246.
Signing certificate SHA-256: c0e2f382bef12fdc2d9ad06e20b80e4d596c79b584ee8fbb70fd5e4bf79787b7.
Private links and hashes: ../outputs/continuum/INSTALL-v17.txt and SHA256SUMS-v17.txt.
Both architectures passed signature, ZIP, server-side SHA-256 and HTTPS range checks.
The signed ARM64 APK installed over signed v16 on Android 16 and retained an app-private file.

## Viewer verification

TikTok gallery GIFs use the available MP4 with original-GIF fallback. On Android 16
the visible GIF rendered a frame, released on swipe, and resumed on return.
The local media server saw one poster request and no original GIF request on
that path. A neighboring post's original photo arrived only after selection.

The regular viewer downloads one distinct, bounded next-image preview after the
current media is ready. With 150 ms preview latency and 750 ms original latency,
the next picture appeared within 56 ms on v17, versus 207–209 ms cold on v16.
Full-resolution readiness stayed near 0.84 s. Data Saving suppressed the prefetch.

Across 18 controlled TikTok swipes per build, median app-process PSS was 236.9 MiB
on v16 and 230.3 MiB on v17. V16 retained up to five video players; v17 kept
the current page and its immediate neighbors (three). Forward median time to
visible playback was 130/125 ms (v16/v17); backward median was 55/90 ms.
The separate single-player experiment delayed visible playback in both viewers.

Sound-only pagination displayed the retry message after an HTTP 503 and the empty
message on exhaustion. The spinner disappeared in both cases. All 126 Android
release regressions and 15 tunnel-policy checks passed. Logs: .scratch/v17-verification/.

## Return point

Install v17 over v16 on the phone and check long gallery/TikTok sessions with
Daniel's usual feeds. Physical-device crash and performance confirmation is open.
Source and device commands are in AGENTS.md. The existing JDK 25, SDK 37 and
medium_phone Android 16 emulator support local verification.
