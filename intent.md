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

Daniel confirmed that the first APK works on his phone. He then requested import
of legacy Reddit /r/a+b+c links, including shorthand forms and case-insensitive
deduplication, into the existing multireddit creation screen. Anonymous imports
must use the existing local Room save flow, without another VPS endpoint.

The first APK's temporary CI signing key was not retained. To protect the working
installation, the second build uses a separate `org.cygnusx1.continuum.proxy`
package, shown as Continuum Proxy, with a persistent private key for future
updates. This is an implementation choice made while the optional installation
preference question was unanswered; it does not authorize deleting the old app.

Daniel reported that imported lists saved but their feeds failed while single
subreddits worked. He directed that these act as separate local subscription
feeds using anonymous Home's existing implementation. Reuse that maintained
local-list/shared-loader path; no server-owned Reddit multireddit is required.

Daniel approved the media-loading enhancements: prefer MP4 for inline GIF
playback when Reddit supplies it, share learned bandwidth estimates across clips,
and preload a bounded amount of one upcoming clip. Keep current quality settings,
respect autoplay/data-saving/privacy gates, retain original GIF fallback, and
deliver an APK update signed with the preserved v2 key.
