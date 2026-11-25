# Android v5 playback signing

The current player endpoint rejects the former v3 Medusa request with an empty
HTTP 200 response. Anonymous re-registration alone does not fix it. Playback now
uses `/novel/player/multi_video_model/preload/v1`, gzip JSON and an independently
implemented Android `v04.10.03` Medusa / Helios / Khronos signature.

## Implementation and provenance

The production build uses the GPL Kotlin signing implementation. No native signing
SDK binary, executable bytecode, VM interpreter, remote signing service or captured
device session is included or needed. The earlier JNI prototype is archived in
ignored local research outputs; production source and build wiring have been removed.
`V5Constants` contains wire-format constants
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

### Issue #102: pure Kotlin playback recovery (2026-11-23)

Production playback now uses the current 74532 App request profile and the existing
Kotlin V5 implementation with wire field 21 set to 312 (zigzag encoding 624).
The internal SDK meaning of this field is not established. Successful official App
and standalone SDK messages shared this value; the former Kotlin value was 377.

The same URL, gzip body, identity, timestamp and transport were retained during
live A/B checks. Replacing only field 21 in the otherwise successful native Medusa
with 377 returned 200/0B. Replacing only this field in the otherwise failing Kotlin
Medusa with 312 returned models for both issue samples, including repeated calls.
The first 16 bytes of F13 matched; its final four bytes and runtime metadata still
differ, but transplanting those fields was unnecessary in these tests. A later
measurement recovered playback with an expanded embedded device envelope; see
[Medusa device envelope completeness](#medusa-device-envelope-completeness-2026-11-25).
No captured state or SDK output is used by the Kotlin implementation.

`MobilePlayback.signer` selects the verified current request profile, binds the
exact gzip bytes and final query, and uses the existing bounded ticket selection.
The default model host is reading.snssdk.com; an explicit alternate host is still
honored. App-provided VOD codec negotiation handles ByteVC2-only models without
forwarding signing or account headers. No website API or player is used.

Build normally with `:app:assembleDebug :app:assembleDebugAndroidTest`; no native
SDK option or preparation step is required. On an isolated TV emulator without the
official App, clear only the task's debug package, then run:

```sh
adb shell pm clear com.hongguotv.nativeapp.debug
adb shell am instrument -w -r -e issue102Samples true \
  com.hongguotv.nativeapp.debug.test/com.hongguotv.nativeapp.LivePlaybackInstrumentation
```

Require `PASS pure Kotlin TV playback`, not just adb exit status. The test checks
that no signing SDK asset/library is packaged and uses the production player for
three issue samples, the next episode, decoded progress, seek and pause/resume.
The research record retains native and field-level A/B history separately from
pure Kotlin playback acceptance. See [the evidence and limits](../docs/research/2026-11-23-issue102-nonweb-playback.md).

### Medusa device envelope completeness (2026-11-25)

On 0.16.14 (`ef328f3a`) the primary model POST returned HTTP 200 with a zero-byte
body again, and the field-21 A/B above did not recover it. The remaining
difference included the `env.device` sub-message: `medusaV5` emitted 14 fields.
This change expands it to 36 populated fields through field number 38, based on
observations of the official `v04.10.03` client.

The added fields include register defaults rather than device readings
(`7/8/10 = -888888`, `9 = 3`, `11 = "!notset!"`, `14 = 4`, `16..21` = six
fixed32 zeros, `23 = 41`, `24 = 36`, `29 = -1`, `38 = 31`), plus the process
metadata `28 = now`, the brand/type echoes `31/34/35/36`, and the `f16`/`f5`
device token, which is the 24-character base64url process nonce prefixed with
`A` to reach 25 characters. `env` additionally needs `1 = launch`,
`2/3 = 247235599/247235596`, `5 = 7`, `7 = pid` and `13 = {ts, -2, 200}`; the
counter block becomes `305/4/694367/1621072674`; and the outer and message flags
become `4480000000000000` instead of eight zero bytes.

A shape matrix held the gzip body, identity and timestamp fixed and varied only
the query profile and endpoint. With the complete envelope, the current 74532
profile returned 23934 and 23815 bytes from `preload/v1` and `player/v1`
respectively, while the former 74332 profile returned zero bytes from both. The
current request shape worked with the expanded envelope, so `MobilePlayback`
is untouched by this fix.

The repository's own `:core:run --args="--issue61"` probe then reported five
renditions and decrypted MP4 bytes for consecutive episodes. On an Android 6
`armeabi-v7a` TV (API 23), episode 1 produced a first HEVC frame in 1.9 s at
1920x1080 with `OMX.amlogic.hevc.decoder.awesome`, and `↓`/`↑` switched episodes
in 200-280 ms without leaving the app. `codec_type=1` on the fallback endpoint
returns five `hvc1` renditions on this device, so the existing compatibility
ladder already selects a hardware-decodable stream.

These measurements establish that the expanded envelope works for the tested
requests, not that every added field is individually necessary. A separate
account-playback branch also rendered real guest/account video with the smaller
envelope plus a 25-character F16 process token. Device identity, request profile,
timestamp and episode can affect the result; an HTTP 200 empty body alone does
not identify the rejected field.

The synthetic wire fixture `signing-v5-playback-envelope.json` is independently
decoded and resealed from the earlier public-input fixture with the reviewed
envelope schema. It retains the original query/body digest and entropy and
contains no captured account or device session. The existing independent digest
and carrier vectors remain unchanged.

### Default playback acceptance

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
