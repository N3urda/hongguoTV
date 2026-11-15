# Playback identity recovery

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
