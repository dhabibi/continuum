# Continuum VPS proxy fork

Daniel explicitly requested the combined APK after the September 19 feature
batch. The build/push hold is lifted for this release. Pushing vps-proxy triggers
the APK workflow; use that run instead of dispatching a duplicate. Record actual
build/test evidence in current_state.md.

This repository is Daniel's Continuum fork and the canonical source for its Reddit
proxy. `upstream` is cygnusx-1-org/continuum; push changes only to `origin`.
Read `intent.md` and the local `current_state.md` before substantive work.

Local accounts reuse the anonymous implementation in separate Room databases;
see `architecture.md`. `LocalProfiles` fixes the storage identity for a process
and switching reopens Home through the existing restart helper. Keep new account
identity/navigation caches in that partition and preserve Default's original
files. Do not mutate `Account.ANONYMOUS_ACCOUNT` or bypass the profile-aware
database, preference and feed/resume cache entry points.

The Android app is under `app/`. Keep the API Base URL setting in the existing
API Keys screen, with the existing overrides switch and restart behavior.
The proxy lives in `deploy/reddit-proxy/`; its supporting deployment is on
habibilabs at `/srv/hermes/repos/reddit-proxy`, owned by hermes. Use the configured
SSH alias. Deployment secrets and endpoint configuration stay outside Git.

Build Android with `./gradlew :app:assembleDebug --no-daemon --max-workers=2`.
The upstream assets require cygnusx-1-org/subreddit-lists cloned into the sibling
`../subreddit-lists` directory. The CI workflow pins that dependency's commit.
The manual `Proxy APK` GitHub workflow installs the matching JDK/Android SDK.
Check proxy behavior with `python -m unittest discover -s deploy/reddit-proxy`.
Keep checks focused on changed behavior; preserve upstream build standards.

CI restores the persistent private key from Actions secret
`CONTINUUM_DEBUG_KEYSTORE`. Its local backup is ignored
`.signing/continuum-debug.p12`. Keep that key; never regenerate or publish it.
Changing it prevents Android from accepting updates over an installed build.
Use the `Proxy APK` workflow for distributable APKs: it restores this key before
building. A default local debug build uses the machine's other debug key and is
for compilation checks only; do not distribute it as an update.

Use `.scratch/` for local evidence. The anonymous proxy must remain private to the
tailnet, bound to loopback on the host, with Reddit-only media targets. Preserve
other Tailscale routes and services. Do not restart hermes-gateway.

## Android CLI (Mac)

Google's official CLI is installed with `brew install android/tap/android-cli`.
Use the project skill at `.agents/skills/android-cli/SKILL.md` for CLI/device work.
`android init` installs its global skill; project installation is
`android skills add --project="$PWD" --agent=codex android-cli`.

The existing Adoptium JDK 25 is registered with macOS (`/usr/libexec/java_home -v25`).
The existing SDK is `$HOME/.cache/continuum/android-sdk`; `.androidrc` selects it for
CLI tools and ignored `local.properties` supplies `sdk.dir` to Gradle. Reuse these.
Android Studio is optional and is not installed. Gradle stays on this repo's wrapper.

Verified commands from the project root:
- `android info`
- `android describe --project_dir="$PWD"` (app variants: debug/release/minifiedRelease/beta)
- `android build :app@debug -- --no-daemon --max-workers=2`
- `android emulator list` and `android emulator start medium_phone --headless`
- `android run <arm64-apk-path> --device=<serial>`
- `android layout --device=<serial>`
- `android screen capture --device=<serial> --output=<png-path>`
- `android input --device=<serial> tap 'id=<resource-id>'`
- `android docs search '<Android topic>'`

`medium_phone` is a reusable Android 16 ARM64 emulator. Stop it when idle; retain its
installed image for future verification. Inspect layout before input and visually
inspect each captured screenshot. Fresh devices need the private API endpoint set
before live Reddit checks. Keep endpoint configuration out of Git.
CLI/Gradle local builds are for verification; distribute only the existing CI-signed
`Proxy APK` workflow outputs. The app module and `lint-checks` module are preserved.
