"""Offline contracts: no SDK, signing key, network, or publication required."""
import importlib.util
import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location('release_tv', ROOT / 'scripts/release_tv.py')
r = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(r)


class ReleaseContractTest(unittest.TestCase):
    def setUp(self):
        self.build = 'versionName = env ?: "0.10.0"\nversionCode = env ?: 10\nminSdk = 21'
        self.tag = 'kotlin-v0.10.0'
        self.badging = "package: name='com.hongguotv.nativeapp' versionCode='10' versionName='0.10.0'\nsdkVersion:'21'"
        self.signature = f'Verified using v1 scheme (JAR signing): true\nSigner #1 certificate SHA-256 digest: {r.CERTIFICATE}'

    def test_current_source_version(self):
        build = (ROOT / 'kotlin-tv/app/build.gradle.kts').read_text()
        self.assertEqual(('0.11.1', 13, 21), r.source_version(build, 'kotlin-v0.11.1'))

    def test_source_version(self):
        self.assertEqual(('0.10.0', 10, 21), r.source_version(self.build, self.tag))

    def test_reject_wrong_tag(self):
        for tag in ['kotlin-v0.9.0', '../x', 'kotlin-v0.10.0+1', 'kotlin-v0.10.0-build.1', 'kotlin-v00.10.0']:
            with self.subTest(tag=tag), self.assertRaises(ValueError):
                r.source_version(self.build, tag)

    def test_invalid_source_sdk_code(self):
        for build in [self.build.replace('minSdk = 21', 'minSdk = 26'), self.build.replace('?: 10', '?: 0')]:
            with self.assertRaises(ValueError):
                r.source_version(build, self.tag)

    def test_real_apk_contract(self):
        r.validate_apk(self.badging, self.signature, '0.10.0', 10, 21)
        for badging in [self.badging.replace("'21'", "'26'"), self.badging.replace("'10'", "'9'"), self.badging.replace('nativeapp', 'debug')]:
            with self.assertRaises(ValueError):
                r.validate_apk(badging, self.signature, '0.10.0', 10, 21)

    def test_original_signer_and_v1_required(self):
        for signature in [self.signature.replace(r.CERTIFICATE, '0' * 64), self.signature.replace('true', 'false'), self.signature + '\nSigner #2 certificate SHA-256 digest: ' + r.CERTIFICATE]:
            with self.assertRaises(ValueError):
                r.validate_apk(self.badging, signature, '0.10.0', 10, 21)

    def test_archive_path_guards(self):
        r.safe_source_paths(['LICENSE', '.env.example', 'kotlin-tv/app/build.gradle.kts'])
        for path in ['../secret', '/tmp/key', 'x/release.jks', 'x/keystore.properties', 'x/local.properties', '.env', 'x/.env.production', '.git/config', 'x/build/file', 'x/key.pem']:
            with self.subTest(path=path), self.assertRaises(ValueError):
                r.safe_source_paths([path])

    def manifest(self, **changes):
        args = dict(tag=self.tag, version='0.10.0', code=10, sdk=21, commit='a' * 40,
                    name='hongguotv-kotlin-0.10.0-android5.apk', size=1, digest='b' * 64)
        args.update(changes)
        return r.metadata(**args)

    def test_metadata(self):
        data = self.manifest()
        self.assertEqual(21, data['minSdk'])
        self.assertEqual(10, data['versionCode'])
        self.assertEqual(r.PACKAGE, data['packageName'])
        for changes in [dict(size=0), dict(size=80 * 1024 * 1024 + 1), dict(digest='x'), dict(commit='HEAD'), dict(name='../a.apk'), dict(code=0), dict(sdk=26)]:
            with self.assertRaises(ValueError):
                self.manifest(**changes)

    def test_uploaded_hash_size_state_and_names(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / 'test.apk'
            path.write_bytes(b'abc')
            asset = dict(name=path.name, state='uploaded', size=3, digest='sha256:ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad')
            r.verify_assets([asset], [path])
            for change in [dict(size=2), dict(digest=None), dict(state='starter'), dict(name='other.apk')]:
                with self.assertRaises(ValueError):
                    r.verify_assets([dict(asset, **change)], [path])
            with self.assertRaises(ValueError):
                r.verify_assets([asset, asset], [path])

    def test_repository_and_workflow_contract(self):
        self.assertIn(f'REPOSITORY = "{r.REPOSITORY}"', (ROOT / 'kotlin-tv/core/src/main/kotlin/com/hongguotv/core/AppUpdates.kt').read_text())
        workflow = (ROOT / '.github/workflows/kotlin-release.yml').read_text()
        self.assertIn("tags: ['kotlin-v*']", workflow)
        self.assertNotIn('branches:', workflow)
        self.assertNotIn('workflow_dispatch:', workflow)
        self.assertIn("github.repository == 'N3urda/hongguoTV'", workflow)
        self.assertIn(':core:test :app:testDebugUnitTest :app:lintRelease :app:assembleRelease', workflow)
        self.assertIn('cancel-in-progress: false', workflow)
        for name in ['KEYSTORE_B64', 'STORE_PASSWORD', 'KEY_ALIAS', 'KEY_PASSWORD']:
            self.assertIn('secrets.HONGGUOTV_' + name, workflow)
        self.assertFalse((ROOT / '.github/workflows/kotlin-public-sync.yml').exists())

    def test_latest_preview_version_is_compared_without_loosening_release_tags(self):
        self.assertEqual((0, 10, 1), r.released_native_version('preview-v0.10.1-20261111'))
        with self.assertRaises(ValueError):
            r.version_tuple('preview-v0.10.1-20261111')
        with self.assertRaises(ValueError):
            r.released_native_version('unknown-v99')

    def test_local_publish_requires_both_main_and_tag_to_match(self):
        head = 'a' * 40
        for tag_head in [head, 'b' * 40]:
            refs = f'{head}\trefs/heads/main\n{tag_head}\trefs/tags/{self.tag}'
            with patch.dict(r.os.environ, {}, clear=True), patch.object(r, 'run', side_effect=[f'https://github.com/{r.REPOSITORY}.git', '', head, refs]):
                if tag_head == head:
                    r.validate_local_publish(self.tag)
                else:
                    with self.assertRaises(ValueError):
                        r.validate_local_publish(self.tag)

    def test_publish_forbidden_from_fork_or_branch(self):
        for env in [dict(GITHUB_REPOSITORY='fork/hongguoTV', GITHUB_REF='refs/tags/' + self.tag), dict(GITHUB_REPOSITORY=r.REPOSITORY, GITHUB_REF='refs/heads/main')]:
            with patch.dict(r.os.environ, env), self.assertRaises(ValueError):
                r.publish(self.tag)

    def test_published_rerun_never_uploads(self):
        release = dict(tag_name=self.tag, draft=False)
        with patch.dict(r.os.environ, GITHUB_REPOSITORY=r.REPOSITORY, GITHUB_REF='refs/tags/' + self.tag), patch.object(r, 'prepare', return_value=(self.manifest(), [])), patch.object(r, 'run', return_value=json.dumps([[release]])) as run:
            r.publish(self.tag)
            self.assertEqual(1, run.call_count)
            self.assertIn('--paginate', run.call_args.args)

    def test_older_release_cannot_replace_latest(self):
        release = dict(tag_name='kotlin-v0.11.0', draft=False, prerelease=False, assets=[])
        with patch.dict(r.os.environ, GITHUB_REPOSITORY=r.REPOSITORY, GITHUB_REF='refs/tags/' + self.tag), patch.object(r, 'prepare', return_value=(self.manifest(), [])), patch.object(r, 'run', return_value=json.dumps([[release]])) as run, self.assertRaises(ValueError):
            r.publish(self.tag)
        self.assertEqual(1, run.call_count)

    def test_draft_different_commit_cannot_be_replaced(self):
        release = dict(tag_name=self.tag, draft=True, target_commitish='b' * 40)
        with patch.dict(r.os.environ, GITHUB_REPOSITORY=r.REPOSITORY, GITHUB_REF='refs/tags/' + self.tag), patch.object(r, 'prepare', return_value=(self.manifest(), [])), patch.object(r, 'run', return_value=json.dumps([[release]])) as run, self.assertRaises(ValueError):
            r.publish(self.tag)
        self.assertEqual(1, run.call_count)

    def test_low_version_code_is_rejected(self):
        release = dict(tag_name='kotlin-v0.9.0', draft=False, prerelease=False,
                       assets=[dict(name='update.json')])
        with patch.dict(r.os.environ, GITHUB_REPOSITORY=r.REPOSITORY, GITHUB_REF='refs/tags/' + self.tag), patch.object(r, 'prepare', return_value=(self.manifest(), [])), patch.object(r, 'run', side_effect=[json.dumps([[release]]), json.dumps(dict(versionCode=10001))]) as run, self.assertRaises(ValueError):
            r.publish(self.tag)
        self.assertEqual(2, run.call_count)

    def test_draft_readback_uses_paginated_authenticated_listing(self):
        draft = dict(id=7, tag_name=self.tag, draft=True, assets=[])
        page = [dict(tag_name=f'other-{i}') for i in range(100)]
        with patch.object(r, 'api', side_effect=[page, [draft]]) as api:
            self.assertEqual(draft, r.release_including_draft(self.tag))
            self.assertEqual(['releases?per_page=100&page=1', 'releases?per_page=100&page=2'],
                             [call.args[0] for call in api.call_args_list])

    def test_missing_draft_fails_closed(self):
        with patch.object(r, 'api', return_value=[]), self.assertRaises(ValueError):
            r.release_including_draft(self.tag)

    def test_draft_recovery_validates_before_publishing(self):
        release = dict(tag_name=self.tag, draft=True, target_commitish='a' * 40, assets=[])
        with patch.dict(r.os.environ, GITHUB_REPOSITORY=r.REPOSITORY, GITHUB_REF='refs/tags/' + self.tag), patch.object(r, 'prepare', return_value=(self.manifest(), [])), patch.object(r, 'run', side_effect=[json.dumps([[release]]), '', '']) as run, patch.object(r, 'api', side_effect=[[dict(tag_name=self.tag, draft=True, assets=[])], dict(draft=False, assets=[]), dict(tag_name=self.tag)]), patch.object(r, 'verify_assets') as verify:
            r.publish(self.tag)
            self.assertEqual(2, verify.call_count)
            self.assertIn('upload', run.call_args_list[1].args)
            self.assertIn('--draft=false', run.call_args_list[2].args)

    def test_failed_uploaded_hash_never_publishes(self):
        release = dict(tag_name=self.tag, draft=True, target_commitish='a' * 40, assets=[])
        with patch.dict(r.os.environ, GITHUB_REPOSITORY=r.REPOSITORY, GITHUB_REF='refs/tags/' + self.tag), patch.object(r, 'prepare', return_value=(self.manifest(), [])), patch.object(r, 'run', side_effect=[json.dumps([[release]]), '']) as run, patch.object(r, 'api', return_value=[dict(tag_name=self.tag, draft=True, assets=[])]), patch.object(r, 'verify_assets', side_effect=ValueError('digest mismatch')), self.assertRaises(ValueError):
            r.publish(self.tag)
        self.assertEqual(2, run.call_count)

    def test_api_failure_does_not_create_release(self):
        with patch.dict(r.os.environ, GITHUB_REPOSITORY=r.REPOSITORY, GITHUB_REF='refs/tags/' + self.tag), patch.object(r, 'prepare', return_value=(self.manifest(), [])), patch.object(r, 'run', side_effect=RuntimeError('network/auth failure')) as run, self.assertRaises(RuntimeError):
            r.publish(self.tag)
        self.assertEqual(1, run.call_count)


if __name__ == '__main__':
    unittest.main()
