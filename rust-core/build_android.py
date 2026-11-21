#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""Cross-compile the Rust playback JNI library for Android."""
import argparse
import os
from pathlib import Path
import platform
import shutil
import subprocess

ROOT = Path(__file__).resolve().parent
TARGETS = {
    'armeabi-v7a': ('armv7-linux-androideabi', 'armv7a-linux-androideabi'),
    'arm64-v8a': ('aarch64-linux-android', 'aarch64-linux-android'),
    'x86': ('i686-linux-android', 'i686-linux-android'),
    'x86_64': ('x86_64-linux-android', 'x86_64-linux-android'),
}
LIBRARY = 'libhongguo_playback.so'


def build(ndk, output):
    host = {'Darwin': 'darwin-x86_64', 'Linux': 'linux-x86_64'}.get(platform.system())
    if host is None:
        raise ValueError('Build the Android JNI libraries on Linux or macOS')
    tools = ndk / 'toolchains/llvm/prebuilt' / host / 'bin'
    target_directory = ROOT / 'target'
    for target, linker in TARGETS.values():
        compiler = tools / f'{linker}21-clang'
        if not compiler.is_file():
            raise FileNotFoundError(f'Android NDK compiler is missing: {compiler}')
        linker_variable = f'CARGO_TARGET_{target.upper().replace("-", "_")}_LINKER'
        subprocess.run([
            'cargo', 'rustc', '--manifest-path', str(ROOT / 'Cargo.toml'),
            '--target-dir', str(target_directory), '--target', target, '--lib',
            '--release', '--locked', '--features', 'android-jni', '--',
            '-C', 'link-arg=-Wl,-z,max-page-size=16384',
        ], env={**os.environ, linker_variable: str(compiler)}, check=True)

    for abi, (target, _) in TARGETS.items():
        destination = output / abi
        destination.mkdir(parents=True, exist_ok=True)
        shutil.copy2(target_directory / target / 'release' / LIBRARY, destination)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--ndk', required=True, type=Path)
    parser.add_argument('--output', required=True, type=Path)
    args = parser.parse_args()
    build(args.ndk.resolve(), args.output.resolve())
