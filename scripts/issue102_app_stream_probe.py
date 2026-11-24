#!/usr/bin/env python3
"""Reproduce App/CDN playback without hongguoduanju.com.

Requires a running, initialized com.phoenix.read test App, a matching Frida
server forwarded to localhost, frida-tools, cryptography, curl and FFmpeg.
Does not install Apps, log in, rotate identities, or start a public service.
Private output contains expiring playback URLs: keep it out of Git.

References: yinjg1997/hongguo (App signing oracle),
guoapp-modules/hongguo (fallback URL decoding), Adsryen/zhenguojian (CENC key).
"""

import argparse
import base64
import gzip
import hashlib
import json
import os
from pathlib import Path
import subprocess
import time
from urllib.parse import parse_qsl, urlencode, urlsplit, urlunsplit

import frida
import frida_tools
from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes
from cryptography.hazmat.primitives.padding import PKCS7


SAMPLES = ["7704857946630409240", "7709114791150767128", "7709197486451084312"]
MODEL_URL = "https://reading.snssdk.com/novel/player/multi_video_model/preload/v1"
URL_SALT = bytes.fromhex(
    "4dd4c2e6b83162090e52b3c7a6733ba41cb2462b829ab58a196b39db57177524f"
    "49baf7f08e8d68d26a72e37c1a95a2f1f05a51892aef2949732b62a38aadd58"
)
ORACLE_JS = r"""
rpc.exports.prepare = function(url, stub, ticket) {
    return new Promise(function(resolve, reject) {
        Java.perform(function() {
            try {
                const N = Java.use('com.bytedance.frameworks.baselib.network.http.NetworkParams');
                const full = N.addCommonParams.overload('java.lang.String', 'boolean')
                    .call(N, url, true).toString();
                const headers = {
                    'Content-Type': 'application/json; charset=utf-8',
                    'Content-Encoding': 'gzip', 'Accept': 'application/json',
                    'X-SS-STUB': stub, 'X-SS-REQ-TICKET': ticket, 'X-Xs-From-Web': '0'
                };
                const ua = N.getUserAgent();
                if (ua) headers['User-Agent'] = ua.toString();
                const H = Java.use('java.util.HashMap').$new();
                Object.keys(headers).forEach(function(k) {
                    const a = Java.use('java.util.ArrayList').$new();
                    a.add(headers[k]); H.put(k, a);
                });
                const signed = N.tryAddSecurityFactor(full, H);
                const it = signed.keySet().iterator();
                while (it.hasNext()) {
                    const k = it.next(), name = k.toString();
                    if (['x-khronos', 'x-helios', 'x-medusa'].includes(name.toLowerCase())) {
                        let value = signed.get(k).toString();
                        if (value.startsWith('[') && value.endsWith(']')) value = value.slice(1, -1);
                        headers[name] = value;
                    }
                }
                resolve({url: full, headers: headers});
            } catch (e) { reject(new Error(String(e))); }
        });
    });
};
"""


def save_json(path, value):
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n")
    path.chmod(0o600)


def fetch(url, output, headers=None, body=None):
    # stdin keeps signed URLs and headers out of the process command line.
    lines = ["url = " + json.dumps(url)]
    lines += ["header = " + json.dumps(k + ": " + v) for k, v in (headers or {}).items()]
    if body is not None:
        lines += ['request = "POST"', 'data-binary = "@' + str(body) + '"']
    proc = subprocess.run(
        ["curl", "--silent", "--show-error", "--location", "--max-time", "60",
         "--config", "-", "--output", str(output), "--write-out", "%{http_code}"],
        input="\n".join(lines), capture_output=True, text=True, timeout=65,
    )
    size = output.stat().st_size if output.exists() else 0
    if proc.returncode or proc.stdout.strip() != "200" or not size:
        raise RuntimeError(f"HTTP {proc.stdout.strip() or '?'}; {size} bytes; curl exit {proc.returncode}")
    output.chmod(0o600)
    return size


def decode_url(value, seed=""):
    if value.startswith(("https://", "http://")):
        return value
    raw = base64.b64decode(value)
    if raw.startswith(b"\xa8\x00"):
        if raw[2:4] != b"\x01\x00" or not seed:
            raise ValueError("Unsupported fallback URL version")
        digest = hashlib.sha512(hashlib.sha512(base64.b64decode(seed)).digest() + URL_SALT).digest()
        dec = Cipher(algorithms.AES(digest[:16]), modes.CBC(digest[16:32])).decryptor()
        padded = dec.update(raw[4:]) + dec.finalize()
        unpad = PKCS7(128).unpadder()
        raw = unpad.update(padded) + unpad.finalize()
    address = raw.decode()
    if not address.startswith(("https://", "http://")):
        raise ValueError("Invalid media URL")
    return address


def content_key(spade):
    raw = base64.b64decode(spade)
    if len(raw) < 3:
        raise ValueError("Missing media key")
    tag_len = (raw[0] ^ raw[1] ^ raw[2]) - 48
    size = len(raw) - tag_len - 1
    if tag_len < 1 or size < 33 or size >= len(raw):
        raise ValueError("Unsupported media key structure")
    seed = raw[-tag_len - 2] ^ raw[-tag_len - 1]
    if bytes(x ^ seed for x in raw[-tag_len:]) in (b"app_v2", b"web_v2"):
        raise ValueError("Unsupported media key version")
    previous, decoded = [250, 85], []
    for i, value in enumerate(raw[1:size + 1]):
        decoded.append(((previous[i % 2] ^ value) - 21 - i.bit_count()) & 255)
        previous[i % 2] = value
    key = bytes.fromhex(bytes(decoded[1:33]).decode())
    if len(key) != 16:
        raise ValueError("Invalid media key length")
    return key.hex()


def variants(model):
    entries = model["video_list"]
    return list(entries.values()) if isinstance(entries, dict) else entries


def choose(model):
    choices = []
    for entry in variants(model):
        meta = entry.get("video_meta", entry)
        if meta.get("codec_type", "").lower() in ("h264", "h265", "avc", "hevc", "bytevc1"):
            choices.append((int(meta.get("vwidth", 0)), entry))
    return max(choices, key=lambda x: x[0])[1] if choices else None


def resolve(oracle, episode, output, verify):
    body = {
        "biz_param": {"detail_page_version": 0, "device_level": 2,
                      "disable_digg_stat": False, "disable_video_relate_book": False,
                      "need_all_video_definition": True, "need_mp4_align": False,
                      "use_os_player": False, "use_server_dns": False, "video_platform": 1024},
        "dr_scene": "preload", "mixed_video_id_map": {"1004": [episode]},
    }
    data = gzip.compress(json.dumps(body, separators=(",", ":")).encode(), mtime=0)
    body_file = output / (episode + "-request.gz")
    body_file.write_bytes(data)
    request = oracle.exports_sync.prepare(MODEL_URL, hashlib.md5(data).hexdigest().upper(), str(int(time.time() * 1000)))
    if not {"x-khronos", "x-helios", "x-medusa"}.issubset(k.lower() for k in request["headers"]):
        raise RuntimeError("App did not produce all V5 headers")
    response_file = output / (episode + "-model.json")
    model_bytes = fetch(request["url"], response_file, request["headers"], body_file)
    response = json.loads(response_file.read_text())
    value = response.get("data", {}).get(episode, {}).get("video_model")
    if not value:
        raise RuntimeError("Response has no model for the requested episode")
    model = json.loads(value) if isinstance(value, str) else value
    selected, source = choose(model), "model"
    if selected is None:
        endpoint = model.get("fallback_api", {}).get("fallback_api")
        if not endpoint:
            raise RuntimeError("No compatible codec or App fallback API")
        url = urlsplit(endpoint)
        for codec in ("1", "3"):
            query = dict(parse_qsl(url.query)); query["codec_type"] = codec
            address = urlunsplit((url.scheme, url.netloc, url.path, urlencode(query), ""))
            path = output / (episode + "-codec" + codec + ".json")
            fetch(address, path)
            candidate = json.loads(path.read_text()).get("video_info", {}).get("data", {})
            selected = choose(candidate) if candidate.get("video_list") else None
            if selected:
                model, source = candidate, "app-fallback-codec" + codec
                break
    if selected is None:
        raise RuntimeError("No compatible media returned")
    meta = selected.get("video_meta", selected)
    encryption = selected.get("encrypt_info", selected)
    address = decode_url(selected["main_url"], model.get("key_seed", ""))
    key = content_key(encryption["spade_a"]) if encryption.get("spade_a") else None
    if encryption.get("encrypt") and not key:
        raise RuntimeError("Encrypted media has no supported key")
    save_json(output / (episode + "-stream.private.json"), {"url": address, "key": key})
    result = {"episode_id": episode, "model_bytes": model_bytes, "source": source,
              "duration": model.get("video_duration"), "codec": meta.get("codec_type"),
              "width": meta.get("vwidth"), "height": meta.get("vheight"), "cdn_host": urlsplit(address).hostname}
    if verify:
        media = output / (episode + ".mp4")
        result["download_bytes"] = fetch(address, media)
        expected = int(meta.get("size", 0))
        if expected and expected != result["download_bytes"]:
            raise RuntimeError("CDN size differs from model")
        args = ["ffmpeg", "-nostdin", "-v", "error", "-xerror"]
        if key:
            args += ["-decryption_key", key]
        decoded = subprocess.run(args + ["-i", str(media), "-f", "null", "-"], capture_output=True, timeout=180)
        log = output / (episode + "-decode.log"); log.write_bytes(decoded.stderr)
        result.update(decode_exit=decoded.returncode, decode_log_bytes=len(decoded.stderr))
        if decoded.returncode or decoded.stderr:
            raise RuntimeError("Complete media decode failed; inspect local decode log")
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--serial", required=True)
    parser.add_argument("--frida", default="127.0.0.1:27042")
    parser.add_argument("--episode", action="append", help="Public episode ID; repeat for multiple episodes")
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--verify", action="store_true", help="Download and decode each entire media file")
    args = parser.parse_args()
    episodes = args.episode or SAMPLES
    if not all(x.isdigit() for x in episodes):
        parser.error("Episode IDs must be numeric")
    os.umask(0o077)
    output = args.output.resolve(); output.mkdir(parents=True, exist_ok=True, mode=0o700)
    pid = int(subprocess.check_output([args.adb, "-s", args.serial, "shell", "pidof", "com.phoenix.read"]).split()[0])
    session = frida.get_device_manager().add_remote_device(args.frida).attach(pid)
    bridge = Path(frida_tools.__file__).parent / "bridges" / "java.js"
    script = session.create_script(bridge.read_text() + "\nvar Java = bridge;\n" + ORACLE_JS)
    results = []
    try:
        script.load()
        for episode in episodes:
            try:
                result = resolve(script, episode, output, args.verify)
            except Exception as error:
                # No signed URL/header/key is printed on failure.
                result = {"episode_id": episode, "error_type": type(error).__name__, "failed": True}
            results.append(result)
            print(json.dumps(result, ensure_ascii=False), flush=True)
    finally:
        session.detach()
    save_json(output / "summary.json", results)
    return 1 if any(x.get("failed") for x in results) else 0


if __name__ == "__main__":
    raise SystemExit(main())
