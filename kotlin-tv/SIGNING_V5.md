# Android v5 playback signing

The current player endpoint rejects the former v3 Medusa request with an empty
HTTP 200 response. Anonymous re-registration alone does not fix it. Playback now
uses `/novel/player/multi_video_model/player/v1`, gzip JSON and an independently
implemented Android `v04.10.03` Medusa / Helios / Khronos signature.

## Implementation and provenance

The existing GPL Kotlin signing code remains the implementation base. No native
SDK, JNI wrapper, executable bytecode, VM interpreter, remote signing service or
captured device session is included. `V5Constants` contains wire-format constants
and the application's protocol key, not a user's account credential. Requests
use a freshly registered anonymous identity, shared through `PlaybackDeviceSession`;
no account cookie is sent to the model endpoint.

Local, separately held SDK samples were used as a diagnostic oracle. A local VM
research tool from `it00021hot/hongguo-desktop` was used to observe the SDK digest;
its PolyForm-licensed implementation was **not copied into this repository**.
The emitted branch was reduced to ordinary rotations, additions, XOR and table
lookups in the existing GPL `Signer.hashF13` implementation. The seed is
`0x9d4a97c2`; the 96-round state selection is `(1,3,4,0,6,5,2,0,7,7)`.
The old schedule and round constants remain applicable. The final checksum also
sets bit 5. Synthetic digest vectors from that independent SDK computation cover
all 32 timestamp rotation classes; the tests need no SDK or research tool.

The request ticket is adjusted before signing to select branch 12. The final
encoded query, exact gzip bytes and one timestamp are used together. The search
is bounded at 1024 tickets and cannot modify the episode or registered identity.
Carrier permutations, four timestamp variants and Helios license `646637794`
were checked against locally generated v5 envelopes. The existing GPL carrier
implementation is parameterized rather than replaced by another implementation.
Carrier fixtures use synthetic input and keys, with no captured session data.

## Playback and failure boundaries

The response is selected by the exact episode ID, supporting both flat and
`data.1004` response maps. Existing codec filtering, media decryption and Media3
playback remain in use. Oversized, malformed, rejected and non-playable models
fail explicitly. Empty responses retain the existing recoverable error class.
Foreground registration and model fetch share an eight-second deadline;
speculative fetches have two seconds and cannot register an identity.

`streamLadderWithCompatibility` is the TV's production v5 entry point. The older
transport remains separately named `legacyStreamLadderWithCompatibility` for
its existing compatibility regression tests; it is not used as evidence that
v5 works. Catalog/search signing remains v3.

## Issue #83: video-tab recovery (2026-11-22)

The primary v5 POST returned HTTP 200 with zero bytes on main `c14f3ef`.
Its `video_tab` fallback still used the old v3 signing/profile, so that GET also
returned an empty body. A same-identity probe using v5 for the GET returned
successful models for four episodes across two series and decrypted MP4 headers.
This recovery did not need SDI token exchange or another device registration.
The reason the primary POST became unavailable is not established by this test.

The production fallback now shares the POST's v5 device/version profile and
signs the exact episode query with the digest of **empty bytes**. A null signing
body in the legacy signer produces a zero digest and is not equivalent. The GET
sends no body, Content-Encoding, Content-Type, x-ss-stub or account Cookie.
The existing shared deadline, cancellation, exact-episode parsing, backoff and
foreground-only fallback remain in effect. Catalog and legacy diagnostics retain
their separate signing paths.

Protocol references are GPL-3.0 implementations derived from this project's v5
signer, with later video-tab fixes:

- [hongguo-drama 92df757](https://github.com/jackotom/hongguo-drama/blob/92df757ed7be8ea658ee274a6a429b77f49e54e8/src/native/app-source.js)
- [hongguo-tv 9e7386f](https://github.com/aston314/hongguo-tv/blob/9e7386f1dab840e64779b475a7d2bb5e59ab776f/webos/service/mobile-playback-v5.js)

Regression checks cover the v5 wire version and empty-body digest branch on the
actual production fallback, shared identity/profile, omitted POST headers,
signing the final query, and cancellation between primary and recovery requests.

Validation: 408 core tests and 347 Android app unit tests passed, with no failures
or skipped cases; debug lint and both debug APK builds passed. On the Android 14
ARM64 emulator, `LivePlaybackInstrumentation` passed seven real episodes across
three series, including episodes 54, 55 and 70, decoded 1080p frames, advancing
playback, seek, pause/resume and automatic next episode. This is emulator evidence,
not physical-TV acceptance. The fix is included in 0.16.12.

## Reproducing acceptance

Build `:app:assembleDebug :app:assembleDebugAndroidTest`, install the debug app
and test APK on an Android TV emulator, then run:

```sh
adb shell am instrument -w \
  com.hongguotv.nativeapp.debug.test/com.hongguotv.nativeapp.LivePlaybackInstrumentation
```

This is an explicit real-network acceptance test, excluded from unit tests and
ordinary builds. It refuses a non-debug package or a signed-in account. Success
requires the final `PASS` message, not merely adb exit status. It checks the
production activity/player, exact episode ID, rendered frames, advancing time,
seek, pause/resume, automatic next episode and late episodes. It also opens the
episode comments from the playback menu, requires a successful comment response
while playback advances, then checks focus restoration when the panel closes.
Upstream catalog
and video availability may change; this test does not claim physical-TV or
Android 5 hardware acceptance.
