"""Verify hook migration and prerequisite reporting without changing the host."""

import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest


SCRIPT = Path(__file__).resolve().parents[1] / "onboarding.py"


class OnboardingTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="stove-onboarding-")
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name).resolve()
        self.tools = self.root / "tools"
        self.tools.mkdir()
        self.env = dict(os.environ, HOME=str(self.root),
                        GIT_CONFIG_GLOBAL=str(self.root / "global-git-config"),
                        GIT_CONFIG_NOSYSTEM="1",
                        PATH=f"{self.tools}{os.pathsep}{os.environ['PATH']}")
        self.git("init", "-q")
        (self.root / "lefthook.yml").write_text("pre-commit:\n  jobs:\n    - run: 'true'\n")

    def git(self, *args, check=True):
        return subprocess.run(["git", *args], cwd=self.root, env=self.env,
                              capture_output=True, text=True, check=check)

    def run_setup(self, command, **env):
        return subprocess.run([sys.executable, str(SCRIPT), command],
                              cwd=self.root, env=dict(self.env, **env),
                              capture_output=True, text=True)

    def test_fresh_install_is_repeatable(self):
        for _ in range(2):
            result = self.run_setup("hooks")
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        hook = self.root / ".git/hooks/pre-commit"
        self.assertIn("lefthook", hook.read_text())
        self.assertTrue(os.access(hook, os.X_OK))
        self.assertFalse((self.root / ".git/hooks/pre-commit.old").exists())

    def test_old_gradle_default_override_is_migrated(self):
        self.git("config", "--local", "core.hooksPath", str(self.root / ".git/hooks"))
        self.git("config", "--local", "user.name", "Keep my name")
        hook = self.root / ".git/hooks/pre-commit"
        hook.write_text("#!/bin/sh\n# Previous Gradle hook\n")
        result = self.run_setup("hooks")
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertEqual(self.git("config", "--local", "--get", "core.hooksPath", check=False).returncode, 1)
        self.assertEqual(self.git("config", "--local", "--get", "user.name").stdout.strip(), "Keep my name")
        self.assertIn("Previous Gradle hook", hook.with_suffix(".old").read_text())

    def test_custom_hooks_path_is_preserved(self):
        self.git("config", "--local", "core.hooksPath", "custom-hooks")
        custom = self.root / "custom-hooks/pre-commit"
        custom.parent.mkdir()
        custom.write_text("# My custom hook\n")
        result = self.run_setup("hooks")
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(self.git("config", "--local", "--get", "core.hooksPath").stdout.strip(), "custom-hooks")
        self.assertEqual(custom.read_text(), "# My custom hook\n")

    def test_global_git_settings_are_preserved(self):
        self.git("config", "--global", "core.hooksPath", "global-hooks")
        result = self.run_setup("hooks")
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(self.git("config", "--global", "--get", "core.hooksPath").stdout.strip(), "global-hooks")

    def test_doctor_reports_missing_prerequisites(self):
        for name in ["cc", "protoc", "pkg-config", "docker"]:
            executable = self.tools / name
            executable.write_text(f'''#!{sys.executable}
import os, sys
from pathlib import Path
sys.exit(1 if Path(sys.argv[0]).name == os.environ.get("ONBOARD_FAIL") else 0)
''')
            executable.chmod(0o755)
        result = self.run_setup("doctor")
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        result = self.run_setup("doctor", ONBOARD_FAIL="docker")
        self.assertEqual(result.returncode, 1)
        self.assertIn("MISSING: Docker daemon", result.stdout)
        result = self.run_setup("doctor", ONBOARD_FAIL="pkg-config")
        self.assertEqual(result.returncode, 1)
        self.assertIn("MISSING: PostgreSQL development library", result.stdout)
        self.assertIn("MISSING: OpenSSL development library", result.stdout)


if __name__ == "__main__":
    unittest.main()
