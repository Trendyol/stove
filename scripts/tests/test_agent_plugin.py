"""Exercise plugin version alignment and packaging in an isolated checkout."""

import json
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest
import zipfile


REPOSITORY = Path(__file__).resolve().parents[2]
MANIFESTS = ("plugin.json", ".claude-plugin/plugin.json", ".cursor-plugin/plugin.json")


class AgentPluginTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="stove-agent-plugin-")
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.plugin = self.root / ".agents"
        shutil.copytree(REPOSITORY / ".agents", self.plugin)
        (self.root / "scripts").mkdir()
        self.script = self.root / "scripts/package-agent-plugin.py"
        shutil.copy2(REPOSITORY / "scripts/package-agent-plugin.py", self.script)
        self.properties = self.root / "gradle.properties"
        self.properties.write_text("group=com.trendyol\nversion=1.2.3\n")
        for relative in MANIFESTS:
            path = self.plugin / relative
            manifest = json.loads(path.read_text())
            manifest["version"] = "1.2.3"
            path.write_text(json.dumps(manifest, indent=2) + "\n")
        self.output = self.root / "dist/stove-agent-plugin.zip"

    def run_script(self, *args):
        return subprocess.run(
            [sys.executable, str(self.script), *map(str, args)],
            cwd=self.root, capture_output=True, text=True,
        )

    def manifest_bytes(self):
        return {name: (self.plugin / name).read_bytes() for name in MANIFESTS}

    def test_archive_is_self_contained_and_aligned(self):
        before = self.manifest_bytes()
        result = self.run_script("--output", self.output)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(self.manifest_bytes(), before)
        with zipfile.ZipFile(self.output) as archive:
            names = archive.namelist()
            self.assertEqual(names, sorted(names))
            self.assertNotIn("plugins/marketplace.json", names)
            for name in MANIFESTS:
                self.assertEqual(json.loads(archive.read(name))["version"], "1.2.3")
            for path in (self.plugin / "skills").rglob("*"):
                if path.is_file():
                    name = path.relative_to(self.plugin).as_posix()
                    self.assertEqual(archive.read(name), path.read_bytes())

    def test_stove_version_drift_does_not_overwrite_archive_or_manifests(self):
        self.properties.write_text("version=1.2.4\n")
        self.output.parent.mkdir()
        self.output.write_bytes(b"previous archive")
        before = self.manifest_bytes()
        result = self.run_script("--output", self.output)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("Stove 1.2.4", result.stderr)
        self.assertIn("--sync-version", result.stderr)
        self.assertEqual(self.manifest_bytes(), before)
        self.assertEqual(self.output.read_bytes(), b"previous archive")

    def test_adapter_drift_is_rejected(self):
        path = self.plugin / ".claude-plugin/plugin.json"
        document = json.loads(path.read_text())
        document["version"] = "0.0.1"
        path.write_text(json.dumps(document))
        result = self.run_script("--output", self.output)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn(".claude-plugin/plugin.json", result.stderr)
        self.assertFalse(self.output.exists())

    def test_sync_preserves_metadata_and_can_package_in_one_command(self):
        self.properties.write_text("# Stove version\nversion = 2.3.4-RC.1\n")
        before = {name: json.loads(data) for name, data in self.manifest_bytes().items()}
        catalog = self.plugin / "plugins/marketplace.json"
        catalog_before = catalog.read_bytes()
        result = self.run_script("--sync-version", "--output", self.output)
        self.assertEqual(result.returncode, 0, result.stderr)
        with zipfile.ZipFile(self.output) as archive:
            for name, expected in before.items():
                expected["version"] = "2.3.4-RC.1"
                self.assertEqual(json.loads((self.plugin / name).read_text()), expected)
                self.assertEqual(json.loads(archive.read(name)), expected)
        self.assertEqual(catalog.read_bytes(), catalog_before)

    def test_sync_without_output_is_repeatable(self):
        self.properties.write_text("version=1.2.4\n")
        result = self.run_script("--sync-version")
        self.assertEqual(result.returncode, 0, result.stderr)
        before = self.manifest_bytes()
        result = self.run_script("--sync-version")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(self.manifest_bytes(), before)
        self.assertFalse(self.output.exists())

    def test_missing_empty_or_duplicate_stove_version_fails_before_sync(self):
        before = self.manifest_bytes()
        for content in ("group=com.trendyol\n", "version=\n", "version=1.2.3\nversion=1.2.4\n"):
            with self.subTest(content=content):
                self.properties.write_text(content)
                result = self.run_script("--sync-version")
                self.assertNotEqual(result.returncode, 0)
                self.assertIn("exactly one non-empty version", result.stderr)
                self.assertEqual(self.manifest_bytes(), before)

    def test_invalid_adapter_does_not_partially_sync(self):
        self.properties.write_text("version=1.2.4\n")
        (self.plugin / ".cursor-plugin/plugin.json").write_text("{invalid")
        before = self.manifest_bytes()
        result = self.run_script("--sync-version")
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(self.manifest_bytes(), before)

    def test_missing_entrypoint_and_component_are_rejected(self):
        for relative in ("skills/stove/SKILL.md", "mcp.json"):
            with self.subTest(relative=relative):
                path = self.plugin / relative
                original = path.read_bytes()
                path.unlink()
                result = self.run_script("--output", self.output)
                self.assertNotEqual(result.returncode, 0)
                self.assertIn("missing", result.stderr)
                self.assertFalse(self.output.exists())
                path.write_bytes(original)

    def test_symlinked_resources_are_rejected(self):
        external = self.root / "external.md"
        external.write_text("Outside the plugin")
        (self.plugin / "skills/stove/external.md").symlink_to(external)
        result = self.run_script("--output", self.output)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("self-contained", result.stderr)
        self.assertFalse(self.output.exists())

    def test_name_drift_is_not_hidden_by_sync(self):
        path = self.plugin / ".cursor-plugin/plugin.json"
        document = json.loads(path.read_text())
        document["name"] = "unrelated"
        path.write_text(json.dumps(document))
        before = self.manifest_bytes()
        result = self.run_script("--sync-version")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("name must match", result.stderr)
        self.assertEqual(self.manifest_bytes(), before)

    def test_requires_an_explicit_action(self):
        result = self.run_script()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("provide --output", result.stderr)


if __name__ == "__main__":
    unittest.main()
