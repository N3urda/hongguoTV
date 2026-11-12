#!/usr/bin/env python3
"""Build the AAC-only Media3 FFmpeg extension, with pinned source and Android NDK."""
import argparse
import hashlib
import os
from pathlib import Path
import platform
import shutil
import subprocess
import tarfile

VERSION = '6.1.6'
SHA256 = 'd4fcb164028dd3beee5d92c0ac72e46aac6973c75ea12dc14de07bf8f407370a'
NDK_VERSION = '27.1.12297006'
MODULE = Path(__file__).resolve().parents[1]
ABIS = {
    'armeabi-v7a': ('arm', 'armv7-a', 'armv7a-linux-androideabi'),
    'arm64-v8a': ('aarch64', 'armv8-a', 'aarch64-linux-android'),
    'x86': ('x86', 'i686', 'i686-linux-android'),
    'x86_64': ('x86_64', 'x86-64', 'x86_64-linux-android'),
}


def source_archive():
    archive = MODULE / 'build' / f'ffmpeg-{VERSION}.tar.xz'
    archive.parent.mkdir(parents=True, exist_ok=True)
    if not archive.exists():
        temporary = archive.with_suffix('.download')
        try:
            # curl's default retry set excludes TLS/connection resets (exit 35).
            # Bound both each transfer and the retry window on ephemeral CI runners.
            subprocess.run(['curl', '-fL', '--retry', '3', '--retry-all-errors',
                            '--retry-max-time', '300', '--connect-timeout', '30',
                            '--max-time', '120',
                            f'https://ffmpeg.org/releases/ffmpeg-{VERSION}.tar.xz',
                            '-o', str(temporary)], check=True)
            if hashlib.sha256(temporary.read_bytes()).hexdigest() != SHA256:
                raise ValueError(f'FFmpeg source checksum mismatch: {temporary}')
            temporary.replace(archive)
        finally:
            temporary.unlink(missing_ok=True)
    if hashlib.sha256(archive.read_bytes()).hexdigest() != SHA256:
        raise ValueError(f'FFmpeg source checksum mismatch: {archive}')
    return archive


def build(ndk, abis):
    properties = (ndk / 'source.properties').read_text()
    if f'Pkg.Revision = {NDK_VERSION}' not in properties:
        raise ValueError(f'Use Android NDK {NDK_VERSION}')
    host = {'Darwin': 'darwin-x86_64', 'Linux': 'linux-x86_64'}.get(platform.system())
    if host is None:
        raise ValueError('Native build requires Linux or macOS (use WSL on Windows)')
    tools = ndk / 'toolchains/llvm/prebuilt' / host / 'bin'
    archive = source_archive()
    source = MODULE / 'build' / f'ffmpeg-{VERSION}'
    if not source.exists():
        with tarfile.open(archive) as tar:
            tar.extractall(archive.parent, filter='data')
    jni = MODULE / 'src/main/jni/ffmpeg_jni.cc'
    stamp = hashlib.sha256(Path(__file__).read_bytes() + jni.read_bytes() + SHA256.encode() + NDK_VERSION.encode()).hexdigest()
    for abi in abis:
        arch, cpu, triple = ABIS[abi]
        output = MODULE / 'build/native-libs' / abi / 'libffmpegJNI.so'
        marker = output.with_suffix('.stamp')
        if output.exists() and marker.exists() and marker.read_text() == stamp:
            continue
        work = MODULE / 'build/native-work' / abi
        # A changed build configuration must not reuse stale object files.
        if work.exists():
            shutil.rmtree(work)
        work.mkdir(parents=True)
        output.parent.mkdir(parents=True, exist_ok=True)
        cc = tools / f'{triple}21-clang'
        cxx = tools / f'{triple}21-clang++'
        args = [str(source / 'configure'), '--target-os=android', f'--arch={arch}', f'--cpu={cpu}',
                f'--cc={cc}', f'--cxx={cxx}', f'--ar={tools / "llvm-ar"}', f'--nm={tools / "llvm-nm"}',
                f'--ranlib={tools / "llvm-ranlib"}', f'--strip={tools / "llvm-strip"}',
                '--enable-cross-compile', '--enable-pic', '--enable-static', '--disable-shared',
                '--disable-doc', '--disable-programs', '--disable-everything', '--disable-network',
                '--disable-avdevice', '--disable-avformat', '--disable-swscale', '--disable-postproc',
                '--disable-avfilter', '--disable-symver', '--disable-autodetect', '--enable-swresample',
                '--enable-decoder=aac']
        if abi.startswith('x86'):
            args.append('--disable-asm')
        subprocess.run(args, cwd=work, check=True)
        subprocess.run(['make', f'-j{min(os.cpu_count() or 2, 8)}'], cwd=work, check=True)
        # idirafter avoids FFmpeg's VERSION file shadowing C++ <version> on macOS.
        subprocess.run([str(cxx), '-shared', '-fPIC', '-std=c++11', '-O2', '-idirafter', str(source),
                        '-iquote', '.', str(jni), '-Wl,--start-group',
                        'libswresample/libswresample.a', 'libavcodec/libavcodec.a', 'libavutil/libavutil.a',
                        '-Wl,--end-group', '-llog', '-landroid', '-lm', '-Wl,-Bsymbolic',
                        '-Wl,-z,max-page-size=16384', '-static-libstdc++', '-o', str(output)], cwd=work, check=True)
        subprocess.run([str(tools / 'llvm-strip'), str(output)], check=True)
        marker.write_text(stamp)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--ndk', required=True, type=Path)
    parser.add_argument('--abis', nargs='+', choices=list(ABIS), default=list(ABIS))
    options = parser.parse_args()
    build(options.ndk.resolve(), options.abis)
