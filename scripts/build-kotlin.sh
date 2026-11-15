#!/usr/bin/env bash
set -euo pipefail
repo_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
if [[ -z "${JAVA_HOME:-}" ]]; then
  for native_jdk in "$HOME"/.cache/hongguotv/toolchain/jdk-17*/Contents/Home; do
    if [[ -x "$native_jdk/bin/java" ]]; then export JAVA_HOME="$native_jdk"; break; fi
  done
fi
if [[ -z "${ANDROID_HOME:-}" && -d "$HOME/.cache/hongguotv/android-sdk" ]]; then
  export ANDROID_HOME="$HOME/.cache/hongguotv/android-sdk"
fi
for legacy in true false; do
  "$repo_dir/kotlin-tv/gradlew" -p kotlin-tv :core:test :app:testDebugUnitTest :app:lintRelease :app:assembleRelease -PlegacyAudio="$legacy" --console=plain
done
python3 "$repo_dir/kotlin-tv/tools/verify_audio_packages.py" release
mkdir -p "$repo_dir/outputs"
native_version="${HONGGUOTV_VERSION_NAME:-$(sed -n 's/.*versionName = .*?: "\([^"]*\)".*/\1/p' "$repo_dir/kotlin-tv/app/build.gradle.kts")}"
[[ "$native_version" =~ ^[0-9][0-9A-Za-z.+-]*$ ]] || { echo "Invalid version" >&2; exit 1; }
cp "$repo_dir/kotlin-tv/app/build/outputs/apk/release/app-release.apk" "$repo_dir/outputs/hongguotv-kotlin-$native_version-android5.apk"
cp "$repo_dir/kotlin-tv/app/build/platform/outputs/apk/release/app-universal-release.apk" "$repo_dir/outputs/hongguotv-kotlin-$native_version-android6.apk"
cp "$repo_dir/kotlin-tv/app/build/platform/outputs/apk/release/app-armeabi-v7a-release.apk" "$repo_dir/outputs/hongguotv-kotlin-$native_version-android6-arm32-v7a.apk"
cp "$repo_dir/kotlin-tv/app/build/platform/outputs/apk/release/app-arm64-v8a-release.apk" "$repo_dir/outputs/hongguotv-kotlin-$native_version-android6-arm64-v8a.apk"
