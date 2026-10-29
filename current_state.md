# Current state

October 29, 2026: v15 is published at the existing private APK endpoint.
APK/source commit: 69d3cf164df35fe167428e630daf24ed059453a3.
Signed CI: https://github.com/dhabibi/continuum/actions/runs/40959080393 (passed).
Package org.cygnusx1.continuum.proxy; 8.3.1.8-vps.15-debug; versionCode 244.
Signing certificate: c0e2f382bef12fdc2d9ad06e20b80e4d596c79b584ee8fbb70fd5e4bf79787b7.
Private links/hashes: ../outputs/continuum/INSTALL-v15.txt and SHA256SUMS-v15.txt.
Install over the existing app with Tailscale connected. Refresh existing feeds once
so cached posts gain the new GIF metadata; no uninstall or settings reset.

## Verified GIF repair

The feed includes gallery GIF players in its shared playback budget and refreshes
selection after layout/focus changes. Reddit is_gif video metadata is retained;
those animations follow GIF autoplay while ordinary-video preferences remain intact.
Streaming galleries now have one preview owner. Video dimensions govern fit/zoom;
late stills cannot overwrite them. Pinch/pan no longer trigger toolbar clicks.
Original downloads, GIF fallback, Data Saving/blur gates and profile data remain.

113 release regressions passed. Android 16 testing covered cached gallery previews,
correct aspect ratio, actual pinch without toolbar toggles, preload and lifecycle.
A real r/gifs feed animated without scrolling: 148,618 of 292,500 sampled interior
pixels changed. Cold-cache adjacent preloading read 1,048,674 bytes.
Both signed APKs passed package/version/certificate/ZIP, remote full-hash, and
HTTPS range/type/size/byte checks. Evidence: .scratch/v15-verification/.
Physical phone verification remains pending.

## Android CLI setup

Official Homebrew Android CLI 1.1.16717390 is installed; global/project Codex skills
are installed, with commands in AGENTS.md. Existing JDK 25 and SDK 37 are reused.
Java registration, ignored local.properties and ~/.androidrc are configured.
Describe, build, run, layout, screenshot, input and docs lookup worked. The app and
lint-checks modules and all four variants were inspected; debug was built/tested.
The reusable medium_phone Android 16 ARM64 emulator is stopped; its image is retained.
Android Studio is not installed and was not required. No setup intervention remains.

## Return point

Daniel installs v15 and checks feed/gallery behavior on the phone.
VPS services/routing were unchanged; no server restart. Comments remain excluded.
Prior phone crash reports and arbitrary WebRTC UDP enforcement are not claimed fixed.
The canonical intent.md owner draft and .serena/ remain preserved and uncommitted.
