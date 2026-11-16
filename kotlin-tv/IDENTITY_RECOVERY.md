# Playback identity recovery

Historical compatibility-path notes. Since the v5 repair on 2026-11-16, the TV
uses the pure Kotlin mobile model path documented in [SIGNING_V5.md](SIGNING_V5.md).
The compatibility implementation below remains separately named
`legacyStreamLadderWithCompatibility`; identity rotation alone did not restore
live playback and is not the current playback fix.

The foreground compatibility path now replaces an explicitly rejected persisted
player identity once and retries the current episode inside the existing deadline.
Only the business-code rejection already recognized by this path (10001, 10002,
110001) marks identity expiry. Generic HTTP 401/403 does not trigger registration.
This does not establish the meaning of an unknown upstream code or the cause of
issue #53's screenshot, whose installed version remains unconfirmed.

Recovery clears the rejected player slot and local upcoming-model cache. Account
credentials, favorites and history are untouched. Repositories share a lock and
read the persisted identity before refreshing, so a concurrent successful refresh
is reused. Failed registration or rejection of the replacement leaves no rejected
identity persisted and applies a 30-second process-local cooldown. Speculative
preloading never registers. Normal retries retain the existing player loading UI
and position handling; no new UI or periodic refresh timer was added.

Scope: streamLadderWithCompatibility's persisted PlayerDeviceSource fallback.
The separate anonymous PlaybackDeviceSession already has its own bounded rotation;
its primary-route error classification is unchanged.

Validation: 376 core JVM tests passed, including six new scenarios for recovery,
failed registration, rejected replacement, concurrent refresh, generic HTTP 403,
and speculative requests. The success case creates a second repository to verify
reuse of the persisted replacement. No live upstream or affected-TV validation was
performed. No issue comment or release was published.

## Issue #57: compatibility empty-response recovery (2026-11-16)

Based on main `54b91e6`. In addition to the explicit-expiry recovery above,
foreground playback now replaces the persisted compatibility identity once when
both compatibility model endpoints return empty bodies. It then retries the same
episode within the original model deadline. This is a bounded recovery attempt,
not a claim that an empty response proves identity expiry.

Empty-response rotations share a 30-second monotonic, process-local cooldown
across repositories using the same device store. A concurrent replacement is
reused. The old slot remains intact if registration fails; a successful replacement
is persisted even if its model requests are also empty. Further frontend requests
can try again after cooldown. Speculative requests never rotate. Mixed failures,
video-info-only empty responses, and explicit `Retry-After` backoff do not trigger
this recovery. Cancellation is checked before registration and persistence.

Diagnostics now traverse bounded, cycle-safe cause and suppressed chains so the
primary failure survives compatibility fallback and initial registration failures.
Compatibility model traces include `CS` (shortplay) / `CM` (multi-model), a host
alias, HTTP status, response bytes, transport protocol and recognized business
codes. `compat-reg=ok|failed|cooldown` describes the empty-response recovery outcome.
No URL, device identity, cookie or response body is added. The display/log cap is
640 characters so combined paths are not cut off at the old 180-character limit.

Regression coverage includes persistence across repository recreation, successful
recovery, persistent empties, failed registration, concurrent refresh, cancellation,
speculative loading, mixed responses, server backoff, video-info stage preservation,
shared model/registration deadline, suppressed diagnostics, cyclic exceptions and
full diagnostic-line rendering. Live upstream and the reporter's F3 Android 6.0
TV remain unverified; these checks use controlled HTTP fixtures.

Validation for this change: `:core:test` (387 tests),
`:app:testDebugUnitTest` (314 tests), `:app:lintDebug`, and `git diff --check`
passed. Tests used the default Android 6+ platform configuration (`legacyAudio=false`).
No APK release or physical-device acceptance was performed.
