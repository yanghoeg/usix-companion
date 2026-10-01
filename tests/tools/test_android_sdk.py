import contextlib
import hashlib
import io
import stat
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "tools"))
import prepare_android_sdk as sdk


class SdkPreparationTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.cache = Path(self.temporary.name)
        self.patches = patch.multiple(sdk, CACHE=self.cache, SDK=self.cache / "sdk")
        self.patches.start()
        self.addCleanup(self.patches.stop)

    def archive(self, names):
        target = self.cache / "fixture.zip"
        with zipfile.ZipFile(target, "w") as z:
            for name, mode, value in names:
                entry = zipfile.ZipInfo(name)
                entry.external_attr = mode << 16
                z.writestr(entry, value)
        return hashlib.sha256(target.read_bytes()).hexdigest()

    def test_verified_archive_installs_metadata_and_executable(self):
        checksum = self.archive([
            ("package/source.properties", stat.S_IFREG | 0o644, "Pkg.Revision=1"),
            ("package/compiler", stat.S_IFREG | 0o755, "synthetic executable"),
        ])
        with contextlib.redirect_stdout(io.StringIO()):
            sdk.install("fixture.zip", "build-tools", "fixture", checksum)
        target = self.cache / "sdk/build-tools/fixture"
        self.assertTrue((target / "source.properties").is_file())
        self.assertEqual(0o755, (target / "compiler").stat().st_mode & 0o777)

    def test_checksum_mismatch_prevents_extraction(self):
        self.archive([("package/source.properties", stat.S_IFREG | 0o644, "fixture")])
        with self.assertRaisesRegex(ValueError, "checksum mismatch"):
            sdk.install("fixture.zip", "build-tools", "fixture", "0" * 64)
        self.assertFalse((self.cache / "sdk").exists())

    def test_traversal_and_symlinks_never_escape_cache(self):
        for name, mode in (("package/../../escaped", stat.S_IFREG | 0o644),
                           ("/absolute", stat.S_IFREG | 0o644),
                           ("package/link", stat.S_IFLNK | 0o777)):
            with self.subTest(name=name):
                checksum = self.archive([(name, mode, "fixture")])
                with self.assertRaises(ValueError):
                    sdk.install("fixture.zip", "build-tools", "fixture", checksum)
                self.assertFalse((self.cache / "sdk/build-tools/fixture").exists())


if __name__ == "__main__":
    unittest.main()
