#!/usr/bin/env python3
"""Verify actual native-library packaging, not merely a Gradle property."""
import argparse
from pathlib import Path
import zipfile

ABIS = {'armeabi-v7a', 'arm64-v8a', 'x86', 'x86_64'}


def verify(apk, platform_only):
    with zipfile.ZipFile(apk) as archive:
        if 'AndroidManifest.xml' not in archive.namelist():
            raise ValueError('Not an APK: missing AndroidManifest.xml')
        libraries = {name for name in archive.namelist()
                     if name.startswith('lib/') and name.endswith('/libffmpegJNI.so')}
    expected = set() if platform_only else {f'lib/{abi}/libffmpegJNI.so' for abi in ABIS}
    if libraries != expected:
        raise ValueError(f'Unexpected FFmpeg libraries: {sorted(libraries)}; expected {sorted(expected)}')
    return sorted(libraries)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('apk', type=Path)
    parser.add_argument('--platform-only', action='store_true')
    args = parser.parse_args()
    print(f'{args.apk}: FFmpeg libraries = {verify(args.apk, args.platform_only)}')
