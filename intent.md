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

After confirming v3 videos/GIFs are very fast, Daniel identified remaining image
loading delays and asked for improvements. The image work adds a cached-preview
handoff, foreground priority, display-sized preview selection and less eager
gallery loading, while preserving original image quality/download URLs. Daniel
then explicitly asked to hold the next APK build while he adds more features.
Keep this batch in source until he requests the combined build.

The next requested app features are vertical post navigation and horizontal
gallery navigation in Shadowbox (without vertical swipe-to-dismiss there), legacy
link import into regular local subscriptions/Home, multiple local accounts with
independent subreddit/multireddit/follow lists, an expandable Multireddits drawer
list, and a discoverable subreddit search screen.

Daniel set a usage boundary for this work: stop at a completed feature around 55%
remaining, or by about 50% remaining. Preserve a clear source checkpoint and the
unfinished requests at that boundary. The APK build/push hold still applies.

Daniel subsequently requested "Build the apk", lifting that hold for the combined
image and five-feature release. Build and deliver an update signed with the
preserved key, using the existing private VPS download location.

After v4, Daniel requested easy moves of multireddits between local profiles,
direct profile buttons in the sidebar instead of a popup switcher, TikTok-style
Shadowbox playback with autoplay/seeking/volume controls, and richer subreddit
discovery with concise descriptions, most-active sorting and add-to-multireddit
buttons. Keep the personal-use flows direct and preserve the existing profile
data, media performance and signing identity. The usage stop boundary remains.
Daniel explicitly confirmed that both relevance and activity sorting must remain
available in subreddit search.
