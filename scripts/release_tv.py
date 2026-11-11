#!/usr/bin/env python3
"""Single-repository release tooling. Importing this module has no side effects."""
import argparse
import hashlib
import json
import os
import re
import shutil
import subprocess
import zipfile
import importlib.util
from pathlib import Path

REPOSITORY = 'N3urda/hongguoTV'
PACKAGE = 'com.hongguotv.nativeapp'
CERTIFICATE = '3579f7a8a91bbbc44688db60fb16a5561921784af23fa92f39b849086d76ca98'
ROOT = Path(__file__).resolve().parents[1]
NOTES = '首页进入即播放，返回可选集；右滑提前补充卡片，统一首页与目录视觉并保留顶部导航；补齐相关剧集及预约封面，新增制作方入口和经核验的作品列表。官方作品搜索暂不可用时提供提示与重试，暂不保证完整作品目录。继续支持 Android 5.0+ 和原证书覆盖升级。'


def require(condition, message):
    if not condition:
        raise ValueError(message)


def version_tuple(tag):
    match = re.fullmatch(r'kotlin-v(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)', tag)
    require(match is not None, 'Expected stable tag kotlin-vMAJOR.MINOR.PATCH')
    return tuple(map(int, match.groups()))


def source_version(text, tag):
    version_tuple(tag)
    version = re.search(r'versionName\s*=.*\?:\s*"([^"]+)"', text)
    code = re.search(r'versionCode\s*=.*\?:\s*(\d+)', text)
    sdk = re.search(r'minSdk\s*=\s*(\d+)', text)
    require(version and code and sdk, 'Missing source version or SDK')
    require(tag == 'kotlin-v' + version[1], 'Tag does not match source versionName')
    require(0 < int(code[1]) <= 2147483647, 'Invalid versionCode')
    require(int(sdk[1]) == 21, 'Release must retain API21 compatibility')
    return version[1], int(code[1]), int(sdk[1])


def safe_source_paths(paths):
    for name in paths:
        path = Path(name)
        require(not path.is_absolute() and '..' not in path.parts, 'Unsafe archive path')
        require(not any(part in ('build', '.gradle', '.kotlin', '.git', 'node_modules') for part in path.parts), 'Build/cache tracked in source')
        require(not name.endswith(('.jks', '.keystore', '.pem', '.p12', 'keystore.properties', 'local.properties')) and not any(part.startswith('.env') and part != '.env.example' for part in path.parts), 'Local credential material tracked in source')


def validate_apk(badging, signature, version, code, sdk):
    package = re.search(r"^package: name='([^']+)' versionCode='([^']+)' versionName='([^']+)'", badging, re.M)
    require(package and package.groups() == (PACKAGE, str(code), version), 'APK package/version mismatch')
    minimum = re.search(r"^sdkVersion:'(\d+)'", badging, re.M)
    require(minimum and minimum[1] == str(sdk), 'APK minSdk mismatch')
    certs = re.findall(r'^Signer #\d+ certificate SHA-256 digest: ([0-9a-fA-F]+)$', signature, re.M)
    require(certs == [CERTIFICATE], 'APK must use the original release certificate')
    require(re.search(r'^Verified using v1 scheme .*: true$', signature, re.M), 'API21 requires verified v1 signing')


def sha256(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def metadata(tag, version, code, sdk, commit, name, size, digest):
    require(tag == 'kotlin-v' + version, 'Metadata tag mismatch')
    version_tuple(tag)
    require(name == f'hongguotv-kotlin-{version}-android5.apk', 'Unsafe APK filename')
    require(re.fullmatch(r'[0-9a-f]{40}', commit), 'Invalid source commit')
    require(re.fullmatch(r'[0-9a-f]{64}', digest), 'Invalid APK digest')
    require(0 < size <= 80 * 1024 * 1024 and sdk == 21 and 0 < code <= 2147483647, 'Invalid APK size/SDK/versionCode')
    return dict(schema=1, packageName=PACKAGE, versionCode=code, versionName=version,
                minSdk=sdk, tag=tag, sourceCommit=commit,
                apk=dict(name=name, size=size, sha256=digest), notes=NOTES)


def verify_assets(assets, files):
    require(len(assets) == len(files), 'Unexpected or missing release assets')
    for path in files:
        matches = [asset for asset in assets if asset['name'] == path.name]
        require(len(matches) == 1, 'Missing/duplicate uploaded asset')
        asset = matches[0]
        require(asset['state'] == 'uploaded' and asset['size'] == path.stat().st_size
                and asset.get('digest') == 'sha256:' + sha256(path), 'Uploaded asset hash/size mismatch')


def run(*args):
    return subprocess.check_output(args, cwd=ROOT, text=True).strip()


def api(endpoint, *args):
    return json.loads(run('gh', 'api', f'repos/{REPOSITORY}/{endpoint}', *args))


def release_including_draft(tag):
    # GitHub's /releases/tags/{tag} endpoint only resolves published releases.
    # Authenticated release listings include drafts, including older recovery drafts.
    page = 1
    while True:
        batch = api(f'releases?per_page=100&page={page}')
        found = next((release for release in batch if release['tag_name'] == tag), None)
        if found is not None:
            return found
        require(len(batch) == 100, 'Release missing from authenticated release listing')
        page += 1


def prepare(tag):
    version, code, sdk = source_version((ROOT / 'kotlin-tv/app/build.gradle.kts').read_text(), tag)
    commit = run('git', 'rev-parse', 'HEAD')
    require(run('git', 'rev-parse', tag + '^{commit}') == commit, 'Checkout is not tagged commit')
    paths = run('git', 'ls-tree', '-r', '--name-only', 'HEAD').splitlines()
    safe_source_paths(paths)
    out = ROOT / 'outputs'
    out.mkdir(exist_ok=True)
    apk = out / f'hongguotv-kotlin-{version}-android5.apk'
    shutil.copy2(ROOT / 'kotlin-tv/app/build/outputs/apk/release/app-release.apk', apk)
    buildtools = Path(os.environ['ANDROID_HOME']) / 'build-tools/36.0.0'
    validate_apk(run(str(buildtools / 'aapt'), 'dump', 'badging', str(apk)),
                 run(str(buildtools / 'apksigner'), 'verify', '--verbose', '--min-sdk-version', '21', '--print-certs', str(apk)), version, code, sdk)
    with zipfile.ZipFile(apk) as package:
        required = {f'lib/{abi}/libffmpegJNI.so' for abi in ('armeabi-v7a', 'arm64-v8a', 'x86', 'x86_64')}
        require(required <= set(package.namelist()), 'APK missing Android 5 AAC native decoder ABI')
    data = metadata(tag, version, code, sdk, commit, apk.name, apk.stat().st_size, sha256(apk))
    manifest = out / 'update.json'
    manifest.write_text(json.dumps(data, ensure_ascii=False, indent=2) + '\n')
    source = out / f'hongguotv-{tag}-source.zip'
    run('git', 'archive', '--format=zip', f'--prefix=hongguotv-{tag}/', '--output', str(source), 'HEAD')
    # Ship the exact native dependency sources alongside wrapper/relink build scripts.
    spec = importlib.util.spec_from_file_location('native_audio', ROOT / 'kotlin-tv/decoder-ffmpeg/tools/build_native.py')
    native_audio = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(native_audio)
    archive = native_audio.source_archive()
    with zipfile.ZipFile(source, 'a', compression=zipfile.ZIP_STORED) as bundle:
        bundle.write(archive, f'hongguotv-{tag}/third-party/{archive.name}')
    checks = out / 'SHA256SUMS'
    checks.write_text(''.join(f'{sha256(p)}  {p.name}\n' for p in (apk, source, manifest)))
    (out / 'release-notes.md').write_text(f'''红果 TV {version}（versionCode {code}），Android 5.0+。\n\n{NOTES}\n\n原发布证书签名，可同签名覆盖安装并保留本机记录。旧 0.9 更新器不识别 API21 元数据，首次需手动安装，请勿先卸载。\n\n源码提交：{commit}。附 APK、对应完整源码 ZIP（含 GPL-3.0 和第三方许可）、update.json、SHA256SUMS。已通过自动化测试、Release 静态检查和正式签名构建；电视真机兼容性由设备测试继续验证。\n\n构建要求与步骤见源码 kotlin-tv/AUTO_UPDATES.md；版本取自该标签的 Gradle 配置，不使用运行次数改写版本。\n''')
    return data, [apk, source, manifest, checks]


def released_native_version(tag):
    # Earlier native preview builds were sometimes marked stable/latest.
    preview = re.fullmatch(r'preview-v(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)-\d{8}', tag)
    return tuple(map(int, preview.groups())) if preview else version_tuple(tag)


def validate_local_publish(tag):
    version_tuple(tag)
    require(not os.environ.get('GITHUB_ACTIONS'), 'Use workflow publishing inside Actions')
    require(run('git', 'remote', 'get-url', 'origin') in
            (f'https://github.com/{REPOSITORY}.git', f'https://github.com/{REPOSITORY}', f'git@github.com:{REPOSITORY}.git'),
            'Local release requires canonical origin')
    require(not run('git', 'status', '--porcelain'), 'Local release requires a clean checkout')
    head = run('git', 'rev-parse', 'HEAD')
    refs = run('git', 'ls-remote', 'origin', 'refs/heads/main', f'refs/tags/{tag}', f'refs/tags/{tag}^{{}}')
    remote = dict(line.split()[::-1] for line in refs.splitlines())
    require(remote.get('refs/heads/main') == head, 'Local release must match remote main')
    require(remote.get(f'refs/tags/{tag}^{{}}', remote.get(f'refs/tags/{tag}')) == head,
            'Local release tag must already point to remote main')


def publish(tag, local=False):
    if local:
        validate_local_publish(tag)
    else:
        require(os.environ.get('GITHUB_REPOSITORY') == REPOSITORY and os.environ.get('GITHUB_REF') == 'refs/tags/' + tag, 'Publishing only allowed from canonical repository tag workflow')
    data, files = prepare(tag)
    # List successfully before interpreting absence. Authentication/network errors fail closed.
    pages = json.loads(run('gh', 'api', '--paginate', '--slurp', f'repos/{REPOSITORY}/releases?per_page=100'))
    releases = [release for page in pages for release in page]
    existing = next((r for r in releases if r['tag_name'] == tag), None)
    if existing and not existing['draft']:
        print('Already published; leaving all assets and latest unchanged.')
        return
    # Strict stable tags only. Unknown stable formats require manual review, never guess latest.
    published = [r for r in releases if not r['draft'] and not r['prerelease']]
    for release in published:
        # Legacy React Native releases are a different package, not native updates.
        if not release['tag_name'].startswith('kotlin-v'):
            continue
        previous = version_tuple(release['tag_name'])
        require(previous < version_tuple(tag), 'Refusing older/equal version release; latest cannot go backwards')
        if any(a['name'] == 'update.json' for a in release['assets']):
            old = json.loads(run('gh', 'release', 'download', release['tag_name'], '--repo', REPOSITORY, '--pattern', 'update.json', '--output', '-'))
            require(data['versionCode'] > old['versionCode'], 'versionCode must increase beyond published updates')
        else:
            require(previous < (0, 10, 0), 'Published native release missing update.json; review manually')
    if published:
        latest = api('releases/latest')
        require(released_native_version(latest['tag_name']) < version_tuple(tag), 'Latest cannot go backwards')
    notes = ROOT / 'outputs/release-notes.md'
    if existing:
        require(existing['target_commitish'] == data['sourceCommit'], 'Draft belongs to a different commit')
        require(set(a['name'] for a in existing['assets']) <= set(p.name for p in files), 'Draft has unexpected assets; review manually')
    else:
        run('gh', 'release', 'create', tag, '--repo', REPOSITORY, '--verify-tag', '--target', data['sourceCommit'], '--draft', '--title', f'红果 TV {data["versionName"]}', '--notes-file', str(notes))
    # Only drafts are replaceable. Workflow serialization prevents two publishers racing.
    run('gh', 'release', 'upload', tag, '--repo', REPOSITORY, *map(str, files), '--clobber')
    draft = release_including_draft(tag)
    require(draft['draft'], 'Release unexpectedly published before validation')
    verify_assets(draft['assets'], files)
    run('gh', 'release', 'edit', tag, '--repo', REPOSITORY, '--draft=false', '--latest=true', '--notes-file', str(notes))
    result = api('releases/tags/' + tag)
    require(not result['draft'], 'Release did not publish')
    verify_assets(result['assets'], files)
    require(api('releases/latest')['tag_name'] == tag, 'Latest verification failed')
    print(f'Published {tag}: {data["apk"]["sha256"]}')


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('action', choices=['validate', 'prepare', 'publish'])
    parser.add_argument('tag')
    parser.add_argument('--local', action='store_true', help='Publish verified remote main/tag using local signing and gh authentication')
    args = parser.parse_args()
    if args.action == 'validate':
        print(source_version((ROOT / 'kotlin-tv/app/build.gradle.kts').read_text(), args.tag))
    elif args.action == 'prepare':
        prepare(args.tag)
    else:
        publish(args.tag, local=args.local)


if __name__ == '__main__':
    main()
