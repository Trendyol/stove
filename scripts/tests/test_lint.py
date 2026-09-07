"""Exercise lint routing and failure handling without installing project toolchains."""

import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest


class LintTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="stove-lint-")
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.bin = self.root / "tools"
        self.bin.mkdir()
        self.log = self.root / "commands.jsonl"
        self.env = dict(os.environ, HOME=str(self.root),
                        PATH=f"{self.bin}{os.pathsep}{os.environ['PATH']}",
                        LINT_TEST_LOG=str(self.log))
        shutil.copy(Path(__file__).resolve().parents[2] / "lint.sh", self.root)
        recorder = f'''#!{sys.executable}
import json, os, sys
from pathlib import Path
name = Path(sys.argv[0]).name
with open(os.environ["LINT_TEST_LOG"], "a") as log:
    log.write(json.dumps([name, os.getcwd(), sys.argv[1:]]) + "\\n")
sys.exit(1 if name == os.environ.get("LINT_FAIL_TOOL") else 0)
'''
        for name in ["cargo", "npm", "npx", "go", "gofmt"]:
            tool = self.bin / name
            tool.write_text(recorder)
            tool.chmod(0o755)
        for name in ["gradlew", "recipes/jvm/gradlew"]:
            tool = self.root / name
            tool.parent.mkdir(parents=True, exist_ok=True)
            tool.write_text(recorder)
            tool.chmod(0o755)
        for name in ["server/stove-server/spa/node_modules", "go/stove-kafka",
                     "recipes/process/golang/go-showcase"]:
            (self.root / name).mkdir(parents=True)
        (self.root / ".gitignore").write_text("tools/\ncommands.jsonl\n")
        self.write("lib/stove/src/Main.kt")
        self.write("server/stove-server/src/lib.rs")
        self.git("init", "-q")
        self.git("add", ".")
        self.git("-c", "user.name=Lint Test", "-c", "user.email=lint@example.invalid",
                 "-c", "core.hooksPath=/dev/null", "commit", "-qm", "fixture")

    def git(self, *args):
        return subprocess.run(["git", *args], cwd=self.root, env=self.env,
                              check=True, capture_output=True, text=True)

    def write(self, name, content="fixture\n"):
        path = self.root / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content)

    def lint(self, *args, fail=None, cwd=None):
        self.log.unlink(missing_ok=True)
        env = dict(self.env)
        if fail:
            env["LINT_FAIL_TOOL"] = fail
        result = subprocess.run(["sh", str(self.root / "lint.sh"), *args],
                                cwd=cwd or self.root, env=env,
                                capture_output=True, text=True)
        commands = [json.loads(line) for line in self.log.read_text().splitlines()] if self.log.exists() else []
        return result, commands

    def test_tool_failures_reach_exit_status(self):
        for project, tool in [("rust", "cargo"), ("spa", "npx"), ("go", "go"),
                              ("go", "gofmt"), ("jvm", "gradlew"), ("recipes", "gradlew")]:
            with self.subTest(project=project, tool=tool):
                result, commands = self.lint(project, fail=tool)
                self.assertTrue(commands)
                self.assertEqual(result.returncode, 1, result.stdout + result.stderr)
                self.assertNotIn("All checks passed.", result.stdout)

    def test_install_failure_is_not_hidden_by_successful_checks(self):
        (self.root / "server/stove-server/spa/node_modules").rmdir()
        result, _ = self.lint("spa", fail="npm")
        self.assertEqual(result.returncode, 1, result.stdout + result.stderr)

    def test_recipe_changes_do_not_lint_main_build(self):
        self.write("recipes/jvm/example/src/Test.kt")
        result, commands = self.lint()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(len(commands), 1)
        self.assertIn(str(self.root / "recipes/jvm"), commands[0][2])
        self.assertNotIn("--no-daemon", commands[0][2])

    def test_go_recipe_changes_do_not_start_gradle(self):
        self.write("recipes/process/golang/go-showcase/main.go")
        result, commands = self.lint()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual({c[0] for c in commands}, {"go", "gofmt"})

    def test_default_checks_staged_unstaged_and_untracked(self):
        self.write("lib/stove/src/Main.kt", "changed\n")
        self.git("add", "lib/stove/src/Main.kt")
        self.write("server/stove-server/src/lib.rs", "changed\n")
        self.write("server/stove-server/spa/src/new.ts")
        result, commands = self.lint()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual({c[0] for c in commands}, {"gradlew", "cargo", "npx"})
        result, commands = self.lint("--staged")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual([c[0] for c in commands], ["gradlew"])

    def test_empty_index_does_not_lint_unstaged_files(self):
        self.write("server/stove-server/src/lib.rs", "changed\n")
        result, commands = self.lint("--staged")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(commands, [])

    def test_detection_from_subdirectory(self):
        self.write("server/stove-server/src/lib.rs", "changed\n")
        result, commands = self.lint(cwd=self.root / "server/stove-server")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual({c[0] for c in commands}, {"cargo"})

    def test_shared_catalog_checks_both_gradle_builds(self):
        self.write("gradle/libs.versions.toml")
        result, commands = self.lint()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(len(commands), 2)
        self.assertTrue(all(c[0] == "gradlew" for c in commands))

    def test_duplicate_selections_run_once(self):
        result, commands = self.lint("jvm", "jvm")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(len(commands), 1)

    def test_clean_tree_does_no_work(self):
        result, commands = self.lint()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(commands, [])


if __name__ == "__main__":
    unittest.main()
