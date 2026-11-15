# Audio release verification — 2026-11-15

Default: Android 6+ / API 23, no FFmpeg dependency. Compatibility:
`-PlegacyAudio=true`, Android 5 / API 21, FFmpeg for four ABIs.
Both use the existing package and version; no release/version bump was published.

Validated locally:

- 33 Python release/download contract tests passed.
- 372 core JVM tests passed, including SDK-specific update selection and invalid platform metadata rejection.
- Platform app: 307 tests passed. Compatibility app: 377 tests passed, including API 21 cases.
- Both Debug builds and Debug lint passed.
- Both minified Release builds and Release lint passed, using a temporary test certificate, not the production signing key.
- Actual APK inspection: platform minSdk 23, no `.so` or FFmpeg Java descriptors; compatibility minSdk 21, FFmpeg for ARM32, ARM64, x86 and x86_64.
- Release artifact metadata/signature/package validation passed using the temporary certificate override in the test process only. Production certificate enforcement in release_tv.py is unchanged.
- Native PCM growth regression passed under host ASan/UBSan; the earlier append-offset repair is included in the compatibility build.

Release APK sizes with test signing: platform 3,592,251 bytes; compatibility
5,372,190 bytes. These test-signed Release APKs cannot replace production installs.

No affected Android 11 ARM32 hardware was available. These results establish build,
update selection and dependency isolation; they do not establish that issue #55's
ART crash is fixed. Existing clients initially update through the compatibility
APK; immediate removal requires manually installing the properly signed Android 6+
release. Subsequent higher-version updates use SDK-specific selection.
