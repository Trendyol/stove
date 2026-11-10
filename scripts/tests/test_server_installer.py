"""Exercise supported installer platforms without network access."""

import hashlib
import io
import os
from pathlib import Path
import subprocess
import sys
import tarfile
import tempfile
import unittest


INSTALLER = Path(__file__).resolve().parents[2] / "server/stove-server/install.sh"
VERSION = "0.34.0"


class ServerInstallerTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="stove-installer-")
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.tools = self.root / "tools"
        self.tools.mkdir()
        self.destination = self.root / "installed"
        self.destination.mkdir()
        for platform in ("darwin-arm64", "linux-amd64"):
            archive = self.root / f"stove-{VERSION}-{platform}.tar.gz"
            payload = b"#!/bin/sh\necho stove\n"
            with tarfile.open(archive, "w:gz") as tar:
                entry = tarfile.TarInfo("stove")
                entry.size = len(payload)
                entry.mode = 0o755
                tar.addfile(entry, io.BytesIO(payload))
            digest = hashlib.sha256(archive.read_bytes()).hexdigest()
            Path(f"{archive}.sha256").write_text(f"{digest}  {archive.name}\n")
        self.tool("uname", '''import os, sys
print(os.environ["INSTALL_TEST_OS" if sys.argv[1] == "-s" else "INSTALL_TEST_ARCH"])
''')
        self.tool("curl", '''import os, pathlib, shutil, sys
root = pathlib.Path(os.environ["INSTALL_TEST_ROOT"])
with (root / "downloads").open("a") as log:
    log.write(sys.argv[-1] + "\\n")
shutil.copyfile(root / sys.argv[-1].rsplit("/", 1)[1], sys.argv[sys.argv.index("-o") + 1])
''')

    def tool(self, name, source):
        path = self.tools / name
        path.write_text(f"#!{sys.executable}\n{source}")
        path.chmod(0o755)

    def install(self, system, architecture):
        return subprocess.run(
            ["sh", str(INSTALLER), "--version", VERSION, "--dir", str(self.destination)],
            env=dict(os.environ, PATH=f"{self.tools}{os.pathsep}{os.environ['PATH']}",
                     INSTALL_TEST_ROOT=str(self.root), INSTALL_TEST_OS=system,
                     INSTALL_TEST_ARCH=architecture),
            capture_output=True, text=True,
        )

    def test_supported_platforms_install_verified_binary(self):
        for system, architecture, platform in [
            ("Darwin", "arm64", "darwin-arm64"),
            ("Linux", "x86_64", "linux-amd64"),
        ]:
            with self.subTest(platform=platform):
                result = self.install(system, architecture)
                self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
                self.assertTrue(os.access(self.destination / "stove", os.X_OK))
                self.assertIn(f"stove-{VERSION}-{platform}.tar.gz: OK", result.stdout)

    def test_unsupported_platforms_fail_before_downloading(self):
        for system, architecture in [("Darwin", "x86_64"), ("Linux", "aarch64")]:
            with self.subTest(system=system, architecture=architecture):
                result = self.install(system, architecture)
                self.assertNotEqual(result.returncode, 0)
                self.assertIn("Supported platforms: macOS (Apple Silicon), Linux (amd64)", result.stdout)
                self.assertFalse((self.root / "downloads").exists())
                self.assertFalse((self.destination / "stove").exists())


if __name__ == "__main__":
    unittest.main()
