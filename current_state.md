# Current state

October 29, 2026: v16 hotfix (versionCode 245) is verified locally; signed publication pending.
Latest published APK is v15.
APK/source commit: 69d3cf164df35fe167428e630daf24ed059453a3.
Signed CI: https://github.com/dhabibi/continuum/actions/runs/40959080393 (passed).
Package org.cygnusx1.continuum.proxy; 8.3.1.8-vps.15-debug; versionCode 244.
Signing certificate: c0e2f382bef12fdc2d9ad06e20b80e4d596c79b584ee8fbb70fd5e4bf79787b7.
Private links/hashes: ../outputs/continuum/INSTALL-v15.txt and SHA256SUMS-v15.txt.
Install over the existing app with Tailscale connected. Refresh existing feeds once
so cached posts gain the new GIF metadata; no uninstall or settings reset.

## v16 hotfix verification

Daniel reported v15 crashing seconds after opening and possible unwanted video autoplay.
Reproduced v15 on Android 16: preloading called ExoPlayerViewHelper.getPlayer before
lazy player allocation, throwing in VideoAutoplayImpl.isBuffering. Buffering queries
now use a lifecycle-safe predicate, including before play and after release.
Unbound video rows also remain ineligible without dereferencing a missing post.

GIF preview metadata no longer marks a full external video as an animation.
Only native GIF metadata or an explicit original GIF/GIFV source qualifies, and
cached v15 flags require a verified rendition marker before overriding video autoplay.
No cache deletion, preference reset or new Parcel format was introduced.

118 release regressions passed. The same mixed-video feed survived startup, scroll,
background and resume with autoplay enabled. A fresh launch with Video autoplay Never
remained alive and showed ordinary VIDEO posts without autoplay player/control nodes.
V15 gallery geometry, gestures and raw-GIF playback repairs remain unchanged.
Physical phone confirmation is pending. Evidence: .scratch/v16-verification/.

## Android CLI setup

Official Homebrew Android CLI 1.1.16717390 is installed; global/project Codex skills
are installed, with commands in AGENTS.md. Existing JDK 25 and SDK 37 are reused.
Java registration, ignored local.properties and ~/.androidrc are configured.
Describe, build, run, layout, screenshot, input and docs lookup worked. The app and
lint-checks modules and all four variants were inspected; debug was built/tested.
The reusable medium_phone Android 16 ARM64 emulator is stopped; its image is retained.
Android Studio is not installed and was not required. No setup intervention remains.

## Return point

Next: verify signed v16 CI artifacts, publish the private APK, fast-forward main,
then collect phone startup/autoplay results.
VPS services/routing were unchanged; no server restart. Comments remain excluded.
Prior phone crash reports and arbitrary WebRTC UDP enforcement are not claimed fixed.
The canonical intent.md owner draft and .serena/ remain preserved and uncommitted.
