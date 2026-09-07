"""Exercise real just/Lefthook orchestration with stubbed language toolchains."""

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
        self.root = Path(self.temp.name).resolve()
        self.bin = self.root / "tools"
        self.bin.mkdir()
        mise = self.bin / "mise"
        mise.write_text(f'''#!{sys.executable}
import os, sys
assert sys.argv[1:3] == ["exec", "--"], sys.argv
os.execvp(sys.argv[3], sys.argv[3:])
''')
        mise.chmod(0o755)
        self.log = self.root / "commands.jsonl"
        self.env = dict(os.environ, HOME=str(self.root),
                        PATH=f"{self.bin}{os.pathsep}{os.environ['PATH']}",
                        LINT_TEST_LOG=str(self.log), LEFTHOOK="1")
        source = Path(__file__).resolve().parents[2]
        for name in ["justfile", "lefthook.yml", "server/stove-server/justfile"]:
            target = self.root / name
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy(source / name, target)
        recorder = f'''#!{sys.executable}
import json, os, sys
from pathlib import Path
name = Path(sys.argv[0]).name
with open(os.environ["LINT_TEST_LOG"], "a") as log:
    log.write(json.dumps([name, os.getcwd(), sys.argv[1:]]) + "\\n")
if name == "gofmt" and "-l" in sys.argv and os.environ.get("LINT_UNFORMATTED"):
    print("go/stove-kafka/unformatted.go")
sys.exit(1 if name == os.environ.get("LINT_FAIL_TOOL") else 0)
'''
        for name in ["cargo", "npm", "go", "gofmt", "gradlew", "recipes/jvm/gradlew"]:
            tool = (self.root if name.endswith("gradlew") else self.bin) / name
            tool.parent.mkdir(parents=True, exist_ok=True)
            tool.write_text(recorder)
            tool.chmod(0o755)
        for name in ["server/stove-server/spa", "go/stove-kafka",
                     "recipes/process/golang/go-showcase"]:
            (self.root / name).mkdir(parents=True, exist_ok=True)
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

    def run_commands(self, *args, fail=None, unformatted=False, cwd=None):
        self.log.unlink(missing_ok=True)
        env = dict(self.env)
        if fail:
            env["LINT_FAIL_TOOL"] = fail
        if unformatted:
            env["LINT_UNFORMATTED"] = "1"
        result = subprocess.run(args, cwd=cwd or self.root, env=env,
                                capture_output=True, text=True)
        commands = [json.loads(line) for line in self.log.read_text().splitlines()] if self.log.exists() else []
        return result, commands

    def hook(self, **kwargs):
        return self.run_commands("lefthook", "run", "pre-commit", "--no-auto-install", **kwargs)

    def groups(self, commands):
        groups = set()
        for tool, cwd, args in commands:
            if tool == "gradlew":
                groups.add("recipes" if "recipes/jvm" in args else "jvm")
            else:
                groups.add({"cargo": "rust", "npm": "spa", "gofmt": "go", "go": "go"}[tool])
        return groups

    def test_lint_checks_every_group_on_clean_tree(self):
        result, commands = self.run_commands("just", "lint")
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertEqual(self.groups(commands), {"jvm", "recipes", "rust", "spa", "go"})
        for tool, cwd, args in commands:
            if tool == "cargo":
                self.assertEqual(Path(cwd), self.root / "server/stove-server")

    def test_tool_failures_reach_exit_status(self):
        for group, tool in [("rust", "cargo"), ("spa", "npm"), ("go", "go"),
                            ("go", "gofmt"), ("jvm", "gradlew"), ("recipes", "gradlew")]:
            with self.subTest(group=group, tool=tool):
                result, commands = self.run_commands("just", f"lint-{group}", fail=tool)
                self.assertTrue(commands)
                self.assertNotEqual(result.returncode, 0, result.stdout + result.stderr)
        result, _ = self.run_commands("just", "lint-go", unformatted=True)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("unformatted.go", result.stdout)

    def test_hook_selects_projects_from_staged_paths(self):
        cases = [
            ("lib/stove/src/Main.kt", {"jvm"}),
            ("build.gradle.kts", {"jvm"}),
            ("lib/stove/api/stove.api", {"jvm"}),
            ("recipes/jvm/example/src/Test.kt", {"recipes"}),
            ("recipes/process/golang/go-showcase/main.go", {"go"}),
            ("recipes/process/golang/go-showcase/go.mod", {"go"}),
            ("go/stove-kafka/nested/client.go", {"go"}),
            ("server/stove-server/src/lib.rs", {"rust"}),
            ("server/stove-server/Cargo.toml", {"rust"}),
            ("server/stove-server/spa/src/new.ts", {"spa"}),
            ("server/stove-server/spa/package-lock.json", {"spa"}),
            ("gradle/libs.versions.toml", {"jvm", "recipes"}),
            (".editorconfig", {"jvm", "recipes"}),
            ("justfile", {"jvm", "recipes", "rust", "spa", "go"}),
            ("mise.toml", {"jvm", "recipes", "rust", "spa", "go"}),
            ("README.md", set()),
        ]
        for name, expected in cases:
            with self.subTest(path=name):
                self.git("reset", "--mixed", "HEAD")
                path = self.root / name
                self.write(name, path.read_text() + "\n" if path.exists() else "fixture\n")
                self.git("add", name)
                result, commands = self.hook()
                self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
                self.assertEqual(self.groups(commands), expected)

    def test_empty_index_ignores_unstaged_and_untracked_files(self):
        self.write("server/stove-server/src/lib.rs", "changed\n")
        self.write("server/stove-server/spa/src/new.ts")
        result, commands = self.hook()
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertEqual(commands, [])

    def test_parallel_hook_failure_is_not_hidden(self):
        for name in ["lib/stove/src/Main.kt", "server/stove-server/src/lib.rs"]:
            self.write(name, "changed\n")
            self.git("add", name)
        result, commands = self.hook(fail="cargo")
        self.assertNotEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertEqual(self.groups(commands), {"jvm", "rust"})

    def test_deleted_files_still_select_their_project(self):
        self.git("rm", "server/stove-server/src/lib.rs")
        result, commands = self.hook()
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertEqual(self.groups(commands), {"rust"})

    def test_format_keeps_expensive_checks_and_api_updates_separate(self):
        result, commands = self.run_commands("just", "format")
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertEqual(self.groups(commands), {"jvm", "recipes", "rust", "spa", "go"})
        forbidden = {"apiDump", "apiCheck", "detekt", "clippy", "vet", "typecheck", "check"}
        for _, _, args in commands:
            self.assertTrue(forbidden.isdisjoint(args), args)
        result, commands = self.run_commands("just", "api-dump")
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertEqual(commands[0][2], ["apiDump"])


if __name__ == "__main__":
    unittest.main()
