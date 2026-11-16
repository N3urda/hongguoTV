# Issues 59 / 57: pure Kotlin playback verification

Verified on 2026-11-16. The final implementation uses **pure Kotlin v5 signing**;
no official native signing library, SDK bytecode interpreter or remote signing
service is needed. See [SIGNING_V5.md](SIGNING_V5.md) for the implementation,
protocol derivation and reproducible acceptance command.

## Cause and fix

Catalog and episode lists remained available while the previous v3 playback
requests received empty HTTP 200 responses, even with new anonymous devices.
The official app worked on the user's network. Controlled comparisons isolated
incompatible Medusa / Helios signing and the current mobile player request shape.
The accepted path signs the exact gzip body and final query, uses Android v5,
and parses the requested episode from either supported response-map shape.

An extracted native component was used only as an early local diagnostic oracle.
The acceptance below was repeated with the independent Kotlin implementation
and an APK containing no signing library or SDK metadata. The earlier native
prototype result is not being presented as pure-code acceptance.

## Real emulator playback

Android 36 ARM64 TV emulator, this application's production Activity and Media3
player, anonymous registration, no signed-in account. The pure-code acceptance
passed twice; the second run used the final application code.

| Series | Episodes | Decoded dimensions |
| --- | --- | --- |
| 少夫人来自东北3 | 1, 2 | 1080 × 1920 |
| 嫌疑人的秘密2藏金生死线 | 1, 2 | 1080 × 1920 |
| 无敌财神！我觉醒了唯一隐藏职业 | 54, 55, 70 | 1920 × 1080 |

Every episode required its exact media ID, a rendered first frame, nonzero video
dimensions, `isPlaying`, playback beyond five seconds and no playback error.
Seeking to 15 seconds and advancing beyond 18 seconds, pause/resume, manual
selection and automatic episode 1 → 2 transition all passed. A screenshot was
also visually inspected during the final run and showed the decoded program.

Local evidence files (not required by the application):

- `/private/tmp/issue59-pure-final-acceptance.log`
- `/private/tmp/issue59-pure-playing.png`
- `/private/tmp/issue59-pure-checks.log`
- `/private/tmp/issue59-pure-legacy-checks.log`

## Regression and package checks

- Core: 395 tests, zero failures; includes 32 independent synthetic SDK digest
  vectors, all four carrier variants, final-query/body/identity binding and parser,
  cancellation, speculative-registration and response-limit checks.
- Android 6+ platform build: 314 unit tests, lint, debug APK and instrumentation
  APK build passed.
- Android 5 compatibility build: 385 unit tests, lint and debug APK build passed.
- Release tooling: 34 tests passed.
- All four generated app APKs checked: no native signing library or SDK metadata.
  The platform APK has no native libraries; the compatibility build retains its
  existing FFmpeg audio decoder.
- Existing catalog/search signing vectors continue to pass unchanged.
- Homepage startup-cache tests exercise the gzip endpoint and stale episode hints;
  first-install empty registration still recovers through the existing bounded UI
  retry, without switching dramas.

This is emulator acceptance, not physical-TV or Android 5 device playback
acceptance. Upstream availability and validation may change. No GitHub release
or issue closure is implied by these results.

Issues: [#59](https://github.com/N3urda/hongguoTV/issues/59),
[#57](https://github.com/N3urda/hongguoTV/issues/57).
