# Android 5–7 compatibility

The native APK targets API 21 (Android 5.0) and later. Android 4.x is not supported.
This is a source/build compatibility target, not a claim that every TV chipset has passed playback tests.

## Changes

- Keep Media3 1.8.0: its upstream `constants.gradle` declares minSdk 21
  (https://github.com/androidx/media/blob/1.8.0/constants.gradle).
- Use named system services on API 21/22, including the artwork memory budget.
- Use network-request callbacks on API 21–23 and default-network callbacks on API 24+.
  Callbacks recheck the current connection before recovery/prefetch decisions.
- Resolve the connected network on API 21/22 without `activeNetwork` (API 23).
  Phone input and backup still require a private IPv4 Wi-Fi/Ethernet address.
- On API 21–25, launch the system package installer with a scoped FileProvider URI;
  the OS handles its global unknown-sources setting. API 26+ keeps per-app permission.
- APK checksum, package, version and signing-certificate checks remain enabled on all versions.
  `ApplicationInfo.minSdkVersion` is available only on API 24+, where it must match
  update metadata. Earlier versions rely on the OS installer to enforce the APK's
  SDK requirement; metadata is still checked against the running SDK before download.
- Accept minSdk 21+ update metadata and both historical `android8.apk` and new
  `android5.apk` filenames. Reject Android 4.x metadata and malformed filenames.
- Keep Java core-library desugaring enabled for modern Java APIs in core/dependencies.

## Release transition

Old 0.9.0 clients reject metadata below API 26 and only accept `android8.apk` names.
The first API 21 release therefore needs manual **same-signature overwrite installation**
for those clients. Do not advertise minSdk 26 for an APK built with minSdk 21.
Do not uninstall first: uninstalling removes local records. Subsequent updates use the
new metadata parser. A debug APK cannot overwrite the official signed APK.

## Required verification before declaring device support

Run `:core:test :app:lintDebug :app:assembleDebug`, and run release lint/build with the
release signing environment. Do not suppress NewApi or override dependency minSdk errors.
Inspect the merged manifest for minSdk 21 and the APK for `lib/*/*.so`; an APK with no
JNI libraries has no app-native 32-bit/64-bit ABI restriction. Hardware codecs are separate.

On API 21/22, 23, 24/25 and 26+, verify:

1. Install, cold launch, browse posters, open settings and restart with saved settings.
2. Search and play a sample with both video and audio; pause, seek and change episodes.
3. Disconnect/reconnect Wi-Fi or Ethernet, confirm recovery and no callback leak after exit.
4. Phone input and backup/restore QR codes on LAN; reject cellular-only/VPN-only endpoints.
5. Check/download a newer signed APK, reject bad checksum/signature/version/SDK metadata,
   confirm or cancel the system installer, and verify records survive overwrite installation.
6. API 26+: deny then grant per-app install permission; API 21–25: system unknown-sources flow.
7. A 32-bit ARM device and a 64-bit ARM device, including actual chipset decoder behavior.

No device or emulator run is recorded by this compatibility change alone. Build and test
results must be supplied by the final integrated release report; source inspection is not
runtime verification. HTTPS certificate failures on old vendor firmware must not be solved
by disabling TLS verification.
