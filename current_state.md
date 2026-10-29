# Current state

October 29, 2026: v15 (versionCode 244) is verified locally; signed publication pending.
Latest published APK remains v14, source 39fdde8247ffe4f7feca6b4bc042c870f5f2a84c.
Package org.cygnusx1.continuum.proxy; persistent CI signing identity unchanged.
Preserve the canonical intent.md owner draft and .serena/ while syncing this branch.

## v15 regression repair

Daniel reported static feed GIFs and overlapping/scaling glitches in galleries.
The feed now includes gallery GIF players in its default shared playback budget,
refreshes selection after layout/focus changes, and retains Reddit is_gif metadata.
Animated video renditions follow GIF autoplay; normal videos retain their settings.
GIF loops are independent while ordinary-video loop preferences remain honored.

Streaming galleries now have one preview owner. The underlying still viewer is
hidden during MP4 playback; actual video dimensions define fit, zoom and pan.
Late still requests cannot replace those dimensions. User zoom survives release;
pinch/pan no longer also trigger the image's toolbar-click action.
Original downloads, GIF fallback, Data Saving/privacy gates and profile data remain.

113 release regression tests passed. On Android 16, real cached-preview gallery
playback, aspect ratio, pinch, unchanged toolbar state, preload and lifecycle passed.
A real r/gifs feed animated without scrolling: 148,618 of 292,500 interior pixels
changed across captures. Native gallery preloading warmed 1,048,674 bytes from cold cache.
Physical-phone verification remains pending. Evidence is in .scratch/v15-verification/.

## Android CLI setup

Official Homebrew android/tap/android-cli 1.1.16717390 is installed on Apple Silicon.
Global and project Codex android-cli skills are installed; AGENTS.md has commands.
Existing Adoptium JDK 25 and SDK 37 are reused, with user Java registration and ignored
local.properties plus ~/.androidrc. Android Studio was not needed or installed.
Describe, debug build, run, layout, screenshot, input tap and docs lookup all worked.
Reusable medium_phone (Android 16 ARM64) is available; stop it when idle, keep its image.

## Operations and return point

V14 VPS services/routing are unchanged; no restart or server change in this repair.
CLI local APKs are for verification; distribute only the CI-signed Proxy APK outputs.
Next: verify signed CI artifacts, publish v15 private downloads, fast-forward main,
then collect phone results. Comments remain excluded; no broad privacy/protocol claim added.
Private endpoint configuration and installation URLs remain outside Git.
