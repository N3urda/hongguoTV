"""Offline regression tests for the FFmpeg source download used by Android CI."""
import hashlib
import importlib.util
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

SCRIPT = Path(__file__).resolve().parents[1] / 'decoder-ffmpeg/tools/build_native.py'
spec = importlib.util.spec_from_file_location('build_native', SCRIPT)
native = importlib.util.module_from_spec(spec)
spec.loader.exec_module(native)


class NativeDownloadTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.module = Path(self.directory.name)
        self.payload = b'verified FFmpeg source fixture'
        self.archive = self.module / 'build' / f'ffmpeg-{native.VERSION}.tar.xz'
        self.temporary = self.archive.with_suffix('.download')
        for name, value in [('MODULE', self.module),
                            ('SHA256', hashlib.sha256(self.payload).hexdigest())]:
            patcher = patch.object(native, name, value)
            patcher.start()
            self.addCleanup(patcher.stop)

    def test_download_retries_connection_resets_with_bounded_time(self):
        def download(command, check):
            self.assertTrue(check)
            self.assertIn('--retry-all-errors', command)
            for option, value in [('--retry', '3'), ('--retry-max-time', '300'),
                                  ('--connect-timeout', '30'), ('--max-time', '120')]:
                self.assertEqual(command[command.index(option) + 1], value)
            self.assertIn(f'https://ffmpeg.org/releases/ffmpeg-{native.VERSION}.tar.xz', command)
            self.assertNotIn('--insecure', command)
            Path(command[command.index('-o') + 1]).write_bytes(self.payload)
        with patch.object(native.subprocess, 'run', side_effect=download):
            self.assertEqual(native.source_archive(), self.archive)
        self.assertEqual(self.archive.read_bytes(), self.payload)
        self.assertFalse(self.temporary.exists())

    def test_failed_download_does_not_leave_partial_archive(self):
        def download(command, check):
            self.temporary.write_bytes(b'partial')
            raise subprocess.CalledProcessError(35, command)
        with patch.object(native.subprocess, 'run', side_effect=download):
            with self.assertRaises(subprocess.CalledProcessError):
                native.source_archive()
        self.assertFalse(self.archive.exists())
        self.assertFalse(self.temporary.exists())

    def test_checksum_mismatch_is_not_promoted_to_archive(self):
        with patch.object(native.subprocess, 'run',
                          side_effect=lambda *a, **kw: self.temporary.write_bytes(b'wrong')):
            with self.assertRaisesRegex(ValueError, 'checksum mismatch'):
                native.source_archive()
        self.assertFalse(self.archive.exists())
        self.assertFalse(self.temporary.exists())

    def test_verified_archive_is_reused_without_network(self):
        self.archive.parent.mkdir(parents=True)
        self.archive.write_bytes(self.payload)
        with patch.object(native.subprocess, 'run') as download:
            self.assertEqual(native.source_archive(), self.archive)
            download.assert_not_called()

    def test_existing_archive_is_still_verified(self):
        self.archive.parent.mkdir(parents=True)
        self.archive.write_bytes(b'corrupt')
        with patch.object(native.subprocess, 'run') as download:
            with self.assertRaisesRegex(ValueError, 'checksum mismatch'):
                native.source_archive()
            download.assert_not_called()


if __name__ == '__main__':
    unittest.main()
