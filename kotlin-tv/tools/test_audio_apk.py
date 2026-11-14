import tempfile
import unittest
from pathlib import Path
import zipfile
from verify_audio_apk import ABIS, verify


class AudioApkTest(unittest.TestCase):
    def make_apk(self, abis, manifest=True):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        apk = Path(temporary.name) / 'test.apk'
        with zipfile.ZipFile(apk, 'w') as archive:
            if manifest:
                archive.writestr('AndroidManifest.xml', b'test')
            for abi in abis:
                archive.writestr(f'lib/{abi}/libffmpegJNI.so', b'test')
        return apk

    def test_default_retains_all_abis(self):
        self.assertEqual(4, len(verify(self.make_apk(ABIS), False)))

    def test_platform_has_no_ffmpeg(self):
        self.assertEqual([], verify(self.make_apk([]), True))

    def test_platform_rejects_leftover_library(self):
        with self.assertRaises(ValueError):
            verify(self.make_apk(['armeabi-v7a']), True)

    def test_default_rejects_missing_arm32(self):
        with self.assertRaises(ValueError):
            verify(self.make_apk(ABIS - {'armeabi-v7a'}), False)

    def test_not_an_apk(self):
        with self.assertRaises(ValueError):
            verify(self.make_apk([], manifest=False), True)
