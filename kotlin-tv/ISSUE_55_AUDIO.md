# Issue #55: controlled audio isolation, not a confirmed crash fix

Report: https://github.com/N3urda/hongguoTV/issues/55
Baseline: `5c6436718adf0620286791671fecb5497d3fc050` (0.16.2).

The report shows ART detecting heap corruption on an Android 11 ARM32 process.
That stack does not identify the original writer. The baseline already sets
Media3 extension renderer mode to OFF on API 23+, including Android 11.
Bundling `libffmpegJNI.so` alone does not prove it was loaded or decoding.
There is no device reproduction establishing FFmpeg as the cause.

This branch adds an opt-in diagnostic build that removes the FFmpeg dependency
entirely, rather than stripping a signed APK. Normal builds retain the exact
renderer policy and all four FFmpeg ABIs, including the Android 5 AAC workaround.
It does not remove ARM32 support or change native decoder code speculatively.

## Reproducible builds

With the documented JDK/SDK/NDK installed, build each from a clean checkout of
this same commit. Do not reuse a prior APK after a failed build.

```sh
./kotlin-tv/gradlew -p kotlin-tv clean :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
python3 kotlin-tv/tools/verify_audio_apk.py kotlin-tv/app/build/outputs/apk/debug/app-debug.apk
# Save the control APK outside the build directory before cleaning.
./kotlin-tv/gradlew -p kotlin-tv clean :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug -PplatformAudioOnly=true
python3 kotlin-tv/tools/verify_audio_apk.py kotlin-tv/app/build/outputs/apk/debug/app-debug.apk --platform-only
```

`platformAudioOnly` defaults to false and accepts only `true` or `false`.
The diagnostic version name ends in `-platform-audio`. Both debug builds use
`com.hongguotv.nativeapp.debug`, not the production application ID. Existing
production login/data are not shared, so compare these two debug builds to each
other, not a newly installed debug build to a long-used production install.
The comparison CI builds both APKs in one job with the same generated debug
keystore for data-preserving A/B updates. For local builds likewise use the same
debug keystore; unrelated CI runs can have different debug certificates.
Never replace the release signing key. Uninstalling the debug app clears its data.
The branch-specific CI builds both variants, runs tests/lint, and inspects actual
APK ZIP entries. It uploads Actions artifacts only; it cannot publish releases.

## Device evidence still required

1. Record installed APK SHA256, version, signature, device model, SDK and process
   ABI. Match the official issue reproduction before drawing conclusions.
2. On the affected S905X4 Android 11 ARM32 device, use the same episode, quality,
   navigation and repeat count for both builds. Include repeated episode changes
   and seek/reentry, recording time to crash, audio/video behavior and recovery.
3. Collect sanitized full precrash logcat and tombstone, decoder identity and
   process memory maps if accessible. Determine whether `libffmpegJNI.so` was
   actually loaded. Do not share login tokens, signed media URLs or account data.
4. Repeat the A/B sequence rather than treating one successful playback as proof.
   If FFmpeg was never loaded, investigate the active platform codec, other native
   paths and device runtime instead. If native decoding is implicated, reproduce
   with a minimal sample and ARM32 memory instrumentation before changing JNI.

Passing CI proves build/test/packaging behavior, not resolution of the reported
hardware crash. Do not close #55 or claim a fix without device evidence.
