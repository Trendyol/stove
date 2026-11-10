"""Verify the publication gate against complete, missing, and corrupt artifacts."""

import hashlib
from pathlib import Path
import subprocess
import tempfile
import unittest


SCRIPT = Path(__file__).resolve().parents[2] / "server/stove-server/scripts/verify-release-artifacts.sh"
VERSION = "0.34.0.123-SNAPSHOT"
PLATFORMS = ("darwin-arm64", "linux-amd64")


class ReleaseArtifactsTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="stove-release-artifacts-")
        self.addCleanup(self.temp.cleanup)
        self.directory = Path(self.temp.name)
        for platform in PLATFORMS:
            archive = self.archive(platform)
            archive.write_bytes(f"binary for {platform}".encode())
            digest = hashlib.sha256(archive.read_bytes()).hexdigest()
            self.manifest(platform).write_text(f"{digest}  {archive.name}\n")

    def archive(self, platform):
        return self.directory / f"stove-{VERSION}-{platform}.tar.gz"

    def manifest(self, platform):
        return Path(f"{self.archive(platform)}.sha256")

    def verify(self, version=VERSION):
        return subprocess.run(
            ["bash", str(SCRIPT), version, str(self.directory)],
            capture_output=True, text=True,
        )

    def test_complete_artifacts_pass(self):
        result = self.verify()
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        for platform in PLATFORMS:
            self.assertIn(f"{self.archive(platform).name}: OK", result.stdout)

    def test_cancelled_macos_build_blocks_linux_only_publication(self):
        self.archive("darwin-arm64").unlink()
        self.manifest("darwin-arm64").unlink()
        result = self.verify()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("Missing or empty release artifact", result.stderr)

    def test_missing_checksum_blocks_publication(self):
        self.manifest("darwin-arm64").unlink()
        result = self.verify()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn(self.manifest("darwin-arm64").name, result.stderr)

    def test_empty_archive_blocks_publication(self):
        self.archive("linux-amd64").write_bytes(b"")
        result = self.verify()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("Missing or empty release artifact", result.stderr)

    def test_corrupt_archive_blocks_publication(self):
        self.archive("linux-amd64").write_bytes(b"corrupted download")
        result = self.verify()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("FAILED", result.stdout)

    def test_checksum_for_another_platform_is_rejected(self):
        self.manifest("linux-amd64").write_text(self.manifest("darwin-arm64").read_text())
        result = self.verify()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("Invalid checksum manifest", result.stderr)

    def test_malformed_checksum_is_rejected(self):
        self.manifest("linux-amd64").write_text(f"not-a-hash  {self.archive('linux-amd64').name}\n")
        result = self.verify()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("Invalid checksum manifest", result.stderr)

    def test_other_versions_do_not_satisfy_the_gate(self):
        result = self.verify("0.34.0.124-SNAPSHOT")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("Missing or empty release artifact", result.stderr)


if __name__ == "__main__":
    unittest.main()
