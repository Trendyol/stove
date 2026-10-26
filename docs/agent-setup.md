# Agent skills and plugins

Stove's agent skill helps with test setup, assertions, and failure investigation. It works with Codex, Claude Code, Cursor, and OpenCode. The optional MCP connection lets the agent inspect evidence recorded by a running [Stove Server](Components/18-dashboard.md).

## Install and configure from your project

The dashboard's **Connect agent** button opens setup instructions for Codex, Claude Code, Cursor, and OpenCode. Choose **This project** or **All projects**, then copy the command into a terminal on the machine where your agent runs. The same button is available when the dashboard has no test runs yet.

The panel uses the server's configured `public_url` (including any gateway prefix), or the dashboard's browser origin and base path, for the MCP endpoint. Expand **Manual configuration & MCP-only setup** for configuration snippets and destination paths. Cursor also offers an **Open in Cursor** link for MCP installation; use the terminal command to install the skill too.

Opening the panel does not write agent configuration or install skills. Approvals happen in the agent, and the dashboard does not report whether an agent has connected. The hosted demo links to this guide instead of offering a connection to a demo MCP server.

After installing the `stove` binary, run this in your application repository:

```bash
stove skills install --agent claude --configure-mcp
```

Use `codex`, `claude`, `cursor`, or `opencode` for `--agent`. This installs the skill and merges a `stove` MCP connection into that agent's configuration. Start `stove` separately, then restart your agent session. Follow any trust or MCP approval prompts from your agent.

Preview the destinations without downloading or changing anything:

```bash
stove skills install --agent codex --configure-mcp --dry-run
```

For a shared server or a non-default port, pass the full MCP endpoint:

```bash
stove skills install --agent opencode --configure-mcp \
  --mcp-url https://stove.internal/mcp
```

MCP setup preserves other servers, unrelated settings, and comments. It accepts existing JSONC configuration, including `opencode.jsonc`. An invalid configuration or an existing `stove` process-based MCP entry causes setup to stop before replacing skills. Existing authentication headers remain configured. Setup enables the selected connection where the agent has an `enabled` setting; it does not launch the server.

### Destinations

By default, installation resolves the enclosing Git root, even when invoked from a subdirectory.

| Agent | Project skill | Project MCP configuration |
|---|---|---|
| Universal (default) | `.agents/skills/stove/` | None |
| Codex | `.agents/skills/stove/` | `.codex/config.toml` |
| Claude Code | `.claude/skills/stove/` | `.mcp.json` |
| Cursor | `.agents/skills/stove/` | `.cursor/mcp.json` |
| OpenCode | `.agents/skills/stove/` | `opencode.jsonc` when present, otherwise `opencode.json` |

Codex project configuration requires a trusted project. To select a directory explicitly, including a project outside Git:

```bash
stove skills install --path /path/to/my-app --agent cursor --configure-mcp
stove skills install --path .
```

For a user-wide installation:

```bash
stove skills install --global --agent claude --configure-mcp
```

User-wide skills go under `~/.agents/skills/stove/`, or `~/.claude/skills/stove/` for Claude Code. MCP configuration uses `~/.codex/config.toml` (honoring `CODEX_HOME`), `~/.claude.json`, `~/.cursor/mcp.json`, or `~/.config/opencode/opencode.json[c]` (honoring `XDG_CONFIG_HOME`). Global skills and configuration are local to that machine; remote agent environments need their own installation.

### Existing commands and standalone updates

The existing commands retain their behavior:

```bash
stove skills install          # .agents/skills/stove at the Git root
stove skills install --force  # .agents/skills/stove in the current directory
stove --update-skills         # update the project skill during server startup
stove --no-skills-check       # start the server without checking project skills
```

Run the same installation command again to refresh a selected agent or global installation. Omit `--configure-mcp` when only updating skills, for example `stove skills install --agent claude --global`. Skill updates replace that Stove skill directory, including obsolete files; keep project-specific instructions outside it. Other agents' skill directories are left alone.

On ordinary server startup, Stove checks only `.agents/skills/stove` at the current Git root and prompts before updating in an interactive terminal. Noninteractive startup skips changes unless `--update-skills` is supplied. This does not refresh `.claude/skills/stove` or any global installation; rerun the installer for those destinations.

Downloads follow the upstream default branch (currently `main`), independently of the installed binary or plugin version. They may describe newer APIs than your project's dependencies. To use a specific release, check out its tag in a local Stove checkout and install from that skill directory with `--source`. This also supports offline installation and development; the source folder must contain `SKILL.md`:

```bash
stove skills install --path /path/to/my-app --agent claude \
  --source /path/to/stove/.agents/skills/stove
```

Nested references, assets, and scripts are copied too. Invoke bundled scripts through their documented interpreter. Symlinked resources are not supported by the standalone installer.

## Install as a plugin

The repository also provides a versioned plugin with the same skill and a connection to `http://localhost:4040/mcp`. Plugin installation is an alternative to copying standalone skills; avoid installing both copies into the same agent. A plugin supplies the connection configuration, but you still start the Stove Server separately. For custom endpoints, the standalone setup above provides explicit configuration.

### Claude Code

```bash
claude plugin marketplace add Trendyol/stove
claude plugin install stove@stove-plugins
```

For local development from a Stove checkout:

```bash
claude --plugin-dir ./.agents
```

### Codex

```bash
codex plugin marketplace add Trendyol/stove
```

Install `stove` from the `stove-plugins` marketplace using the supported plugin browser, then start a new session. The Codex IDE extension can use the standalone skill installation above.

### Cursor

Import `Trendyol/stove` with **From GitHub Repository** in Customize, then install `stove` from its marketplace. The repository supplies Cursor's marketplace and compatibility manifest, as well as the portable Agent Plugins manifest.

### OpenCode and other agents

Use `stove skills install --agent opencode --configure-mcp`, or copy the entire `stove` skill folder into the agent's documented skill directory. OpenCode's JavaScript/TypeScript plugins are a separate extension mechanism; a native runtime plugin is not required to load this skill.

## How plugin updates reach installations

Editing a skill does not push it into installed agents. Git marketplaces read the configured branch or tag; a marketplace tracking `main` can see unreleased changes, while a pinned tag stays fixed. Installed copies and running sessions refresh according to each platform:

| Installation | Update path |
|---|---|
| Claude Code, Git marketplace | Refresh the marketplace, then update the installed plugin with the commands below. Third-party marketplace auto-update is off by default; enable it in `/plugin` → **Marketplaces** if wanted. |
| Codex | Refresh the marketplace source and update or reinstall the installed copy through the supported plugin browser. Start a new session. For a local marketplace, update the directory its entry points to and restart the app. |
| Cursor | Refresh the imported repository marketplace and update the installed plugin in Customize. Team marketplaces also support **Refresh** and optional **Auto Refresh**; auto-refresh requires the Cursor GitHub App. Start a new agent session; existing Cloud Agents keep their loaded version. |
| Local development or extracted ZIP | Pull the checkout or replace the extracted bundle, then reload/restart the agent. The ZIP has no built-in updater. |

For Claude Code:

```bash
claude plugin marketplace update stove-plugins
claude plugin update stove@stove-plugins
```

Then use `/reload-plugins` or start a new session. Claude uses the manifest version to decide whether to replace a cached Git plugin: pushing changed skill files with the **same version can leave users on the old files**, even after an update command. Local plugins loaded in place behave differently and read their current files on session start.

Stove's startup skill check does not update plugins. When using the plugin, run `stove --no-skills-check` to avoid creating a separate project skill copy. Neither skill nor plugin updates change the installed Stove binary or the application's Maven dependencies.

See the platform documentation for [Codex plugin loading](https://developers.openai.com/plugins/build/plugins), [Claude updates](https://code.claude.com/docs/en/plugins/loading#versions-and-updates), and [Cursor marketplaces](https://cursor.com/docs/plugins). Refresh support and automatic updates depend on the platform and installation source.

## Package and publish

The plugin root is `.agents/`. Its `skills/stove/` directory is also the canonical source used by `stove skills install`; skill content is maintained once. The root `plugin.json` follows [Agent Plugins](https://agent-plugins.org/), and each `SKILL.md` follows [Agent Skills](https://agentskills.io/specification). Claude and Cursor compatibility manifests and repository marketplace catalogs point at the same plugin directory.

`gradle.properties` is the version source for Stove and all three plugin manifests. Bump only Stove's `version`; no manual plugin version edits are needed. CI synchronizes the manifests in its temporary checkout before validation and packaging.

Build a self-contained release archive:

```bash
python3 scripts/package-agent-plugin.py --sync-version --output /tmp/stove-agent-plugin.zip
```

The archive contains the plugin manifests, skill resources, local MCP configuration, README, and license. `--sync-version` updates the local manifests before packaging; omit it to check that they already match `gradle.properties`. The Agent Plugin CI workflow tests packaging, validates the portable schemas, and uploads the archive for review. Version-file changes also trigger that check.

Run the Stove release workflow from `main`. It synchronizes the plugin versions, builds the archive, and commits and pushes changed manifests before publishing. This keeps repository marketplaces, the release tag, and `stove-agent-plugin.zip` aligned. The push uses the existing `BOT_REPO_TOKEN`; if `main` advances or the push is rejected, the workflow stops before publishing. Rerun it from the latest `main` after resolving the cause.

The workflow attaches `stove-agent-plugin.zip` to the same `v<version>` GitHub release. Skill/plugin changes ship with the next Stove release; a skill-only release needs a new Stove patch version so cached plugins detect it. There is no separate plugin version counter.

Publishing the repository marketplace makes it installable by repository URL. Listing in a public directory is a separate submission: see [OpenAI](https://developers.openai.com/plugins/deploy/submission), [Claude](https://code.claude.com/docs/en/plugins/publish), and [Cursor](https://cursor.com/docs/plugins/building#submitting-a-plugin). This repository does not automatically submit marketplace listings.

Before submitting to a public directory, supply the listing metadata required by that platform, including its icon and privacy policy URL. Claude's validator currently reports those as directory advisories; the plugin manifest itself validates.
