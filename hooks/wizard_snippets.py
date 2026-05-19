"""
mkdocs hook: inject Stove wizard snippets into prose pages.

Marker syntax (single-line HTML comment):
    <!--{wizard:snippet id=sys.postgresql parts=gradle,configure,test}-->

`id`    — key under `systems`, `observability`, `frameworks`, `runtimes`, or `tests`
`parts` — comma-separated subset of {gradle, imports, configure, runner, test, plugin}
          (default: all that apply)

Replaces marker with rendered Markdown (admonition + fenced code).

Source of truth: docs/assets/data/setup.json (same file the wizard fetches).
"""

from __future__ import annotations

import json
import re
from pathlib import Path
from textwrap import indent

MARKER_RE = re.compile(
    r"<!--\{wizard:snippet\s+([^}]+?)\}-->", re.IGNORECASE
)

_DATA_CACHE: dict | None = None


def _load(config) -> dict:
    global _DATA_CACHE
    if _DATA_CACHE is not None:
        return _DATA_CACHE
    docs_dir = Path(config["docs_dir"])
    path = docs_dir / "assets" / "data" / "setup.json"
    with path.open("r", encoding="utf-8") as f:
        _DATA_CACHE = json.load(f)
    return _DATA_CACHE


def _parse_attrs(attr_str: str) -> dict[str, str]:
    out: dict[str, str] = {}
    for tok in attr_str.strip().split():
        if "=" in tok:
            k, v = tok.split("=", 1)
            out[k.strip()] = v.strip().strip('"\'')
    return out


def _lookup(data: dict, ident: str) -> dict | None:
    for bucket in ("systems", "observability", "frameworks", "runtimes", "tests"):
        if ident in data.get(bucket, {}):
            entry = dict(data[bucket][ident])
            entry["_bucket"] = bucket
            entry["_id"] = ident
            return entry
    return None


def _render_block(
    title: str, lang: str, code: str, note: str | None = None
) -> str:
    head = f"**{title}**\n\n"
    body = f"```{lang}\n{code}\n```"
    if note:
        body += f"\n\n*{note}*"
    return head + body


def _fmt_gradle(entry: dict) -> str | None:
    gradle = entry.get("gradle") or []
    if not gradle:
        return None
    lines = [f'testImplementation("{g}")' for g in gradle]
    return _render_block("Gradle", "kotlin", "\n".join(lines))


def _fmt_configure(entry: dict) -> str | None:
    cfg = entry.get("configure")
    if not cfg:
        return None
    return _render_block(
        "Stove configuration",
        "kotlin",
        "Stove().with {\n    " + cfg.replace("\n", "\n    ") + "\n}",
    )


def _fmt_runner(entry: dict) -> str | None:
    runner = entry.get("runner")
    if not runner:
        return None
    # Substitute {pkg} placeholder
    rendered = runner.replace("{pkg}", "com.yourcompany.yourapp")
    return _render_block("Application runner", "kotlin", rendered)


def _fmt_test(entry: dict) -> str | None:
    dsl = entry.get("testDsl")
    if not dsl:
        return None
    return _render_block(
        "Test DSL",
        "kotlin",
        "stove {\n    " + dsl.replace("\n", "\n    ") + "\n}",
    )


def _fmt_plugin(entry: dict) -> str | None:
    line = entry.get("pluginLine")
    block = entry.get("pluginBlock")
    if not (line or block):
        return None
    parts = []
    if line:
        parts.append(f"plugins {{\n    {line}\n}}")
    if block:
        parts.append(block)
    return _render_block("Gradle plugin", "kotlin", "\n\n".join(parts))


_FORMATTERS = {
    "gradle": _fmt_gradle,
    "configure": _fmt_configure,
    "runner": _fmt_runner,
    "test": _fmt_test,
    "plugin": _fmt_plugin,
}

_DEFAULT_PARTS = ["gradle", "configure", "runner", "test", "plugin"]


def _render(entry: dict, parts: list[str]) -> str:
    sections: list[str] = []
    for p in parts:
        fn = _FORMATTERS.get(p)
        if not fn:
            continue
        out = fn(entry)
        if out:
            sections.append(out)
    if not sections:
        return ""
    label = entry.get("label", entry.get("_id", ""))
    body = "\n\n".join(sections)
    # Wrap in a Material "info" admonition for visual separation
    indented = indent(body, "    ")
    return (
        f'!!! example "{label} — wizard-synced snippet"\n\n{indented}\n'
    )


def on_page_markdown(markdown: str, **kwargs) -> str:
    config = kwargs.get("config")
    if config is None:
        return markdown
    if MARKER_RE.search(markdown) is None:
        return markdown
    data = _load(config)

    def _sub(m: re.Match) -> str:
        attrs = _parse_attrs(m.group(1))
        ident = attrs.get("id")
        if not ident:
            return f"<!-- wizard:snippet: missing id -->"
        entry = _lookup(data, ident)
        if not entry:
            return f"<!-- wizard:snippet: unknown id '{ident}' -->"
        parts_raw = attrs.get("parts")
        parts = (
            [p.strip() for p in parts_raw.split(",") if p.strip()]
            if parts_raw
            else list(_DEFAULT_PARTS)
        )
        return _render(entry, parts)

    return MARKER_RE.sub(_sub, markdown)
