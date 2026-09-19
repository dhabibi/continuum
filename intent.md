# Intent

Daniel requested on September 19, 2026 that the supplied anonymous Reddit proxy
be deployed on habibilabs and Continuum be forked locally, patched and built into
an APK with a clickable download link.

The app gains an HTTPS API Base URL setting in its existing API Keys screen.
Anonymous Reddit JSON and Reddit-hosted media should flow through the VPS,
including video manifests and range requests. Access uses Tailscale Serve without
an exit node. The service should restart automatically. Retain ordinary upstream
behavior when overrides are disabled. Account login and account actions are not
provided by the supplied anonymous proxy.

Keep the app patch small, preserve existing services, and deliver source plus an
installable APK. Distinguish a successful build and live proxy checks from actual
phone verification.
