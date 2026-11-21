#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""Build and package the Rust desktop app with the platform WebView."""

import argparse
import hashlib
import json
from pathlib import Path
import plistlib
import platform
import shutil
import subprocess
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parent
REPO = ROOT.parent
TARGET_DIR = ROOT / "target"
TARGETS = {
    "aarch64-apple-darwin": "macos-arm64",
    "x86_64-apple-darwin": "macos-x64",
    "x86_64-pc-windows-msvc": "windows-x64",
    "x86_64-pc-windows-gnu": "windows-x64",
}


def run(*args):
    subprocess.run(args, check=True)


def package_version():
    # Cross builds cannot run the packaged executable on the build host.
    metadata = subprocess.check_output(
        [
            "cargo", "metadata", "--no-deps", "--format-version", "1", "--locked",
            "--manifest-path", str(ROOT / "Cargo.toml"),
        ],
        text=True,
    )
    return json.loads(metadata)["packages"][0]["version"]


def package_mac(binary, artifact, staging, version):
    bundle = staging / "Hongguo Rust.app"
    executable = bundle / "Contents" / "MacOS"
    executable.mkdir(parents=True)
    shutil.copy2(binary, executable / "hongguo-desktop")
    resources = bundle / "Contents" / "Resources"
    resources.mkdir()

    # Use the same original brand art as the application window.
    icons = staging / "AppIcon.iconset"
    icons.mkdir()
    square = staging / "brand-square.png"
    source = REPO / "assets" / "brand" / "logo-pixel.png"
    run("sips", "--padToHeightWidth", "345", "345", str(source), "--out", str(square))
    for size in (16, 32, 128, 256, 512):
        for scale in (1, 2):
            pixels = str(size * scale)
            suffix = "@2x" if scale == 2 else ""
            name = f"icon_{size}x{size}{suffix}.png"
            run("sips", "-z", pixels, pixels, str(square), "--out", str(icons / name))
    run("iconutil", "-c", "icns", str(icons), "-o", str(resources / "AppIcon.icns"))
    shutil.copyfile(REPO / "LICENSE", resources / "LICENSE.txt")
    shutil.copyfile(REPO / "kotlin-tv" / "NOTICE.md", resources / "NOTICE.txt")
    info = {
        "CFBundleDevelopmentRegion": "zh_CN",
        "CFBundleDisplayName": "红果短剧",
        "CFBundleExecutable": "hongguo-desktop",
        "CFBundleIconFile": "AppIcon",
        "CFBundleIdentifier": "com.hongguotv.desktop.rust",
        "CFBundleInfoDictionaryVersion": "6.0",
        "CFBundleName": "Hongguo Rust",
        "CFBundlePackageType": "APPL",
        "CFBundleShortVersionString": version,
        "CFBundleVersion": version,
        "LSApplicationCategoryType": "public.app-category.entertainment",
        "NSHighResolutionCapable": True,
    }
    with (bundle / "Contents" / "Info.plist").open("wb") as out:
        plistlib.dump(info, out)
    run("codesign", "--force", "--sign", "-", str(bundle))
    run("codesign", "--verify", "--strict", str(bundle))
    run("ditto", "-c", "-k", "--sequesterRsrc", "--keepParent", str(bundle), str(artifact))


def package_windows(binary, artifact, loader):
    with zipfile.ZipFile(artifact, "w", compression=zipfile.ZIP_DEFLATED) as archive:
        archive.write(binary, "Hongguo Rust.exe")
        if loader:
            archive.write(loader, "WebView2Loader.dll")
        archive.write(REPO / "LICENSE", "LICENSE.txt")
        archive.write(REPO / "kotlin-tv" / "NOTICE.md", "NOTICE.txt")
        archive.writestr(
            "Readme.txt",
            "红果短剧 Rust 桌面版\r\n"
            "解压后运行 Hongguo Rust.exe。需要 Windows x64 的 Microsoft Edge WebView2 运行时。\r\n"
            "应用可浏览、搜索，通过注册源播放正片，也可选择官网公开试看。\r\n",
        )


def package_installer(binary, artifact, staging, loader, bootstrapper, version):
    shutil.copy2(binary, staging / "app.exe")
    shutil.copyfile(bootstrapper, staging / "MicrosoftEdgeWebview2Setup.exe")
    shutil.copyfile(REPO / "LICENSE", staging / "LICENSE.txt")
    shutil.copyfile(REPO / "kotlin-tv" / "NOTICE.md", staging / "NOTICE.txt")
    shutil.copyfile(ROOT / "windows-installer.nsi", staging / "windows-installer.nsi")
    option = "/" if platform.system() == "Windows" else "-"
    args = [
        "makensis",
        f"{option}WX",
        f"{option}DSETUP_NAME={artifact.name}",
        f"{option}DAPP_VERSION={version}",
        f"{option}DFILE_VERSION={version.split('-', 1)[0]}.0",
    ]
    if loader:
        shutil.copyfile(loader, staging / "WebView2Loader.dll")
        args.append(f"{option}DINCLUDE_LOADER")
    subprocess.run([*args, "windows-installer.nsi"], cwd=staging, check=True)


def build(target):
    args = [
        "cargo", "build", "--manifest-path", str(ROOT / "Cargo.toml"),
        "--target-dir", str(TARGET_DIR), "--release", "--locked", "--target", target,
    ]
    if not target.endswith("windows-gnu"):
        run(*args)
        return None

    # webview2-com-sys links the loader dynamically on GNU and statically on MSVC.
    build_output = subprocess.check_output([*args, "--message-format=json-render-diagnostics"], text=True)
    for line in build_output.splitlines():
        message = json.loads(line)
        if message.get("reason") != "build-script-executed":
            continue
        if message["package_id"].split("#")[-1].startswith("webview2-com-sys@"):
            loader = Path(message["out_dir"]) / "x64" / "WebView2Loader.dll"
            if loader.is_file():
                return loader
    raise SystemExit("GNU Windows build did not provide its WebView2Loader.dll")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--target", choices=TARGETS, required=True)
    parser.add_argument("--output", type=Path, default=REPO / "outputs" / "rust-desktop")
    parser.add_argument(
        "--webview2-bootstrapper", type=Path,
        help="Build a Windows Setup EXE with the verified Microsoft Evergreen bootstrapper",
    )
    options = parser.parse_args()
    is_mac = "apple" in options.target
    if is_mac and platform.system() != "Darwin":
        parser.error("macOS packaging needs a macOS host")
    bootstrapper = options.webview2_bootstrapper
    if bootstrapper:
        if is_mac or not bootstrapper.is_file():
            parser.error("a WebView2 bootstrapper file is required for Windows Setup")
        if not shutil.which("makensis"):
            parser.error("Windows Setup requires the NSIS makensis command")
    loader = build(options.target)
    name = "hongguo-desktop" if is_mac else "hongguo-desktop.exe"
    binary = TARGET_DIR / options.target / "release" / name
    if not binary.is_file():
        raise SystemExit(f"{options.target} build did not produce its executable")
    version = package_version()
    out = options.output.resolve()
    out.mkdir(parents=True, exist_ok=True)
    basename = f"Hongguo-Rust-{version}-{TARGETS[options.target]}.zip"
    if bootstrapper:
        basename = f"Hongguo-Rust-{version}-{TARGETS[options.target]}-setup.exe"
    with tempfile.TemporaryDirectory(prefix=".hongguo-", dir=out) as scratch:
        stage = Path(scratch)
        artifact = stage / basename
        if is_mac:
            package_mac(binary, artifact, stage, version)
        elif bootstrapper:
            package_installer(binary, artifact, stage, loader, bootstrapper, version)
        else:
            package_windows(binary, artifact, loader)
        with artifact.open("rb") as stream:
            checksum = hashlib.sha256()
            for chunk in iter(lambda: stream.read(1024 * 1024), b""):
                checksum.update(chunk)
        digest_file = stage / (basename + ".sha256")
        digest_file.write_text(f"{checksum.hexdigest()}  {basename}\n", encoding="utf-8")
        artifact.replace(out / basename)
        digest_file.replace(out / digest_file.name)
    print(out / basename)


if __name__ == "__main__":
    main()
