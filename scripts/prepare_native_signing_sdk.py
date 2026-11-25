#!/usr/bin/env python3
"""Prepare the explicitly supplied ARM64 SDK outside Git; never copy a device/session identity."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import struct
import zipfile

SDK_SHA256 = "c6831ab265a0cb76dacfa3b7041e9ea0f706490106a74e12f39058671cc14fe3"
APK_SHA256 = "c4583f645d72b5022f060038a9fd286117f316c6fa7f46990c088083344a7d92"
IDENTITY_SLOTS = (6, 7, 8, 9, 13)


def reviewed_metadata(apk: Path, channel: str):
    """Extract only the pinned artifact's public SDK licence; never invoke/install the official app."""
    if hashlib.sha256(apk.read_bytes()).hexdigest() != APK_SHA256:
        raise ValueError("Automatic configuration extraction requires the reviewed APK SHA-256")
    with zipfile.ZipFile(apk) as archive:
        dex = archive.read("classes26.dex")
    count, offset = struct.unpack_from("<II", dex, 56)
    candidates = []
    for index in range(count):
        position = struct.unpack_from("<I", dex, offset + index * 4)[0]
        while dex[position] & 0x80:
            position += 1
        position += 1
        end = dex.index(b"\0", position)
        value = dex[position:end]
        # e3.b's MSConfig.Builder literal, in this exact reviewed artifact only.
        if len(value) == 304 and value.startswith(b"10CS/"):
            candidates.append(value.decode("ascii"))
    if len(candidates) != 1 or not channel or len(channel) > 80:
        raise ValueError("Reviewed SDK configuration could not be identified")
    # u2.toNativeValue order; s4 SDK version is obtained from the library at runtime.
    # Mode 5 is the official basic-mode value, not an assertion of full privacy consent.
    return ["8662", "", "", candidates[0], "", channel, "", "", "", "", "0", "-1", "5", "", "0", [], []]


def sanitize_metadata(raw):
    if not isinstance(raw, list) or len(raw) != 17:
        raise ValueError("Expected the reviewed 17-slot SDK configuration")
    if not all(isinstance(value, str) for value in raw[:15]):
        raise ValueError("Invalid SDK configuration slot types")
    if not all(isinstance(value, list) for value in raw[15:]):
        raise ValueError("Invalid SDK extension slot types")
    if raw[0] != "8662" or not raw[3] or raw[12] != "5":
        raise ValueError("SDK configuration does not match the reviewed application and basic collection mode")
    result = list(raw)
    for index in IDENTITY_SLOTS:
        result[index] = ""
    # Extra metadata can contain captured identity; never carry it into the host app.
    result[14] = "0"
    result[15] = []
    result[16] = []
    return result


def prepare(apk: Path, metadata: Path | None, output: Path, channel="update_64"):
    with zipfile.ZipFile(apk) as archive:
        library = archive.read("lib/arm64-v8a/libmetasec_ml.so")
    if hashlib.sha256(library).hexdigest() != SDK_SHA256:
        raise ValueError("Native library does not match the reviewed SDK SHA-256")
    config = sanitize_metadata(json.loads(metadata.read_text(encoding="utf-8")) if metadata else reviewed_metadata(apk, channel))
    config_bytes = json.dumps(config, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
    if output.resolve().is_relative_to(Path(__file__).resolve().parents[1] / "kotlin-tv"):
        raise ValueError("Use an ignored outputs directory, not the application source tree")
    lib_path = output / "jniLibs/arm64-v8a/libmetasec_ml.so"
    config_path = output / "assets/native-signing/sdk-metadata.json"
    for path, content in ((lib_path, library), (config_path, config_bytes)):
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(content)
        os.chmod(path, 0o600)
    (output / "manifest.json").write_text(json.dumps({
        "format": 1, "abi": "arm64-v8a", "sdkSha256": SDK_SHA256,
        "metadataSha256": hashlib.sha256(config_bytes).hexdigest(),
        "identitySlotsCleared": list(IDENTITY_SLOTS),
    }, indent=2) + "\n", encoding="utf-8")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apk", required=True, type=Path)
    parser.add_argument("--metadata", type=Path, help="Optional existing 17-slot configuration; identities are removed")
    parser.add_argument("--channel", default="update_64", help="Channel when extracting the reviewed APK configuration")
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    try:
        prepare(args.apk, args.metadata, args.output, args.channel)
    except (OSError, ValueError, KeyError, zipfile.BadZipFile):
        parser.exit(1, "SDK preparation failed: check the reviewed APK and configuration inputs.\n")
    print("Prepared verified ARM64 SDK; device and account slots cleared.")


if __name__ == "__main__":
    main()
