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
"$repo_dir/kotlin-tv/gradlew" -p "$repo_dir/kotlin-tv" :core:test :app:lintRelease :app:assembleRelease --console=plain
mkdir -p "$repo_dir/outputs"
cp "$repo_dir/kotlin-tv/app/build/outputs/apk/release/app-release.apk" "$repo_dir/outputs/hongguotv-kotlin-0.2.1-android8.apk"
if command -v shasum >/dev/null; then shasum -a 256 "$repo_dir/outputs/hongguotv-kotlin-0.2.1-android8.apk"; else sha256sum "$repo_dir/outputs/hongguotv-kotlin-0.2.1-android8.apk"; fi
