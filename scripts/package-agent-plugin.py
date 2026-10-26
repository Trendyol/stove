#!/usr/bin/env python3
"""Package the canonical Stove skill using the version in gradle.properties."""

import argparse
from dataclasses import dataclass
from itertools import chain
import json
from pathlib import Path
import re
import zipfile


MANIFESTS = ("plugin.json", ".claude-plugin/plugin.json", ".cursor-plugin/plugin.json")
COMPONENTS = (
    "plugin.json", "mcp.json", ".mcp.json", "README.md", "LICENSE",
    ".claude-plugin", ".cursor-plugin", "skills",
)


@dataclass(frozen=True)
class PluginPlan:
    name: str
    version: str
    files: tuple[Path, ...]
    updates: tuple[tuple[Path, str], ...]


def stove_version(repository):
    properties = (repository / "gradle.properties").read_text(encoding="utf-8")
    versions = re.findall(r"^\s*version[ \t]*=([^\r\n]*)", properties, re.MULTILINE)
    if len(versions) != 1 or not versions[0].strip():
        raise ValueError("gradle.properties must declare exactly one non-empty version")
    return versions[0].strip()


def plugin_files(root):
    components = tuple(root / name for name in COMPONENTS)
    for path in components:
        if path.is_symlink():
            raise ValueError(f"plugin resources must be self-contained: {path}")
        if not path.exists():
            raise ValueError(f"missing plugin component: {path.relative_to(root)}")
    entries = tuple(chain.from_iterable((path, *path.rglob("*")) for path in components))
    for path in entries:
        if path.is_symlink():
            raise ValueError(f"plugin resources must be self-contained: {path}")
    if not (root / "skills/stove/SKILL.md").is_file():
        raise ValueError("missing Stove skill entrypoint")
    return tuple(sorted(path for path in entries if path.is_file()))


def prepare_plugin(repository, sync_version=False):
    """Validate the entire package and plan version edits without writing files."""
    root = repository / ".agents"
    version = stove_version(repository)
    files = plugin_files(root)
    manifests = {
        relative: json.loads((root / relative).read_text(encoding="utf-8"))
        for relative in MANIFESTS
    }
    name = manifests["plugin.json"]["name"]
    for relative, adapter in manifests.items():
        if adapter["name"] != name:
            raise ValueError(f"{relative}: name must match plugin.json")
        if not sync_version and adapter.get("version") != version:
            raise ValueError(
                f"{relative}: version must match Stove {version} from gradle.properties; "
                "run python3 scripts/package-agent-plugin.py --sync-version"
            )

    updates = tuple(
        (root / relative, json.dumps(adapter | {"version": version}, indent=2, ensure_ascii=False) + "\n")
        for relative, adapter in manifests.items()
        if adapter.get("version") != version
    )
    return PluginPlan(name=name, version=version, files=files, updates=updates)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, help="Destination ZIP file")
    parser.add_argument(
        "--sync-version", action="store_true",
        help="Update all plugin manifests to the version in gradle.properties",
    )
    args = parser.parse_args()
    if not args.output and not args.sync_version:
        parser.error("provide --output to package or --sync-version to align manifests")

    repository = Path(__file__).resolve().parents[1]
    try:
        plan = prepare_plugin(repository, args.sync_version)
        for path, content in plan.updates:
            path.write_text(content, encoding="utf-8")
    except (OSError, ValueError, KeyError) as error:
        parser.error(str(error))

    if args.sync_version:
        print(f"Plugin manifests aligned with Stove {plan.version}")
    if not args.output:
        return
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(args.output, "w", zipfile.ZIP_DEFLATED) as archive:
        for path in plan.files:
            archive.write(path, path.relative_to(repository / ".agents").as_posix())
    print(f"Packaged {plan.name} {plan.version}: {args.output} ({len(plan.files)} files)")


if __name__ == "__main__":
    main()
