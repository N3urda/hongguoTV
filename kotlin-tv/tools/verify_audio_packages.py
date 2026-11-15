"""Inspect real built APKs for all required release variants (no publishing)."""
import importlib.util
import os
from pathlib import Path
import subprocess
import sys
import zipfile

ROOT = Path(__file__).resolve().parents[2]
if __name__ == '__main__':
    kind = sys.argv[1]
    if kind not in ('debug', 'release'):
        raise ValueError('Expected debug or release')
    spec = importlib.util.spec_from_file_location('release_tv', ROOT / 'scripts/release_tv.py')
    release = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(release)
    for directory, suffix, abis in release.APK_VARIANTS:
        apk = ROOT / 'kotlin-tv/app/build' / directory / f'outputs/apk/{kind}' / release.apk_filename(suffix, kind)
        release.validate_apk_abis(apk, abis)
        if not abis:
            with zipfile.ZipFile(apk) as archive:
                for name in archive.namelist():
                    if name.endswith('.dex') and b'Landroidx/media3/decoder/ffmpeg/' in archive.read(name):
                        raise ValueError(f'{apk}: unexpected FFmpeg Java dependency')
        aapt = Path(os.environ['ANDROID_HOME']) / 'build-tools/36.0.0/aapt'
        badging = subprocess.check_output([str(aapt), 'dump', 'badging', str(apk)], text=True)
        expected = 21 if abis else 23
        if f"sdkVersion:'{expected}'" not in badging:
            raise ValueError(f'{apk}: incorrect minimum SDK')
        print(f'{suffix}: minSdk={expected}, native ABIs={sorted(abis)}, bytes={apk.stat().st_size}')
