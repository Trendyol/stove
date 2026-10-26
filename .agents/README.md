# Stove agent plugin

Configure Stove end-to-end tests, use its system DSL, and investigate failed runs through recorded MCP evidence. Compatible clients load the shared skill from `skills/stove/`.

This directory is a self-contained Agent Plugins package with Claude Code and Cursor compatibility manifests. The repository's marketplace catalogs list it as `stove@stove-plugins`.

For Claude Code:

```sh
claude plugin marketplace add Trendyol/stove
claude plugin install stove@stove-plugins
```

For Codex, add `Trendyol/stove` with `codex plugin marketplace add`, then install `stove` from the plugin browser. Cursor users can import the repository marketplace from Customize.

The plugin connects to `http://localhost:4040/mcp`. Install and start the Stove Server separately; the skill also works when that connection is unavailable. Restart the agent session after installation. Use `stove --no-skills-check` when you manage the skill through a plugin and do not need a separate project copy.

For OpenCode, custom MCP endpoints, or standalone installation:

```sh
stove skills install --agent opencode --configure-mcp
```

See [installation, configuration, and publishing](https://trendyol.github.io/stove/agent-setup/) for all supported agents, project/global scopes, previews, and offline installation. Choose either plugin installation or standalone skills to avoid duplicate skill discovery.

## Updates and versions

The plugin version matches Stove's `version` in `gradle.properties`. A new commit alone may not replace a cached plugin; skill changes ship with a new Stove release version. Refresh the marketplace and update the installed plugin, then reload or restart the agent session. In Claude Code:

```sh
claude plugin marketplace update stove-plugins
claude plugin update stove@stove-plugins
```

Third-party Claude marketplaces do not auto-update unless enabled. Codex and Cursor use their marketplace/plugin refresh flow; local directories and extracted ZIPs must be updated at their source. A marketplace following `main` can include unreleased changes; a pinned tag stays fixed.

Standalone copies instead follow upstream `main` when you rerun `stove skills install` for the same agent and scope. Stove's startup check only covers the project's `.agents/skills/stove`, not global, Claude-specific, or plugin copies. See [update behavior and release instructions](https://trendyol.github.io/stove/agent-setup/#how-plugin-updates-reach-installations).

## Data handling

The plugin's MCP configuration connects directly to the user's local Stove Server. It adds no hosted service or telemetry. MCP tools return recorded test evidence to the selected agent; that evidence is processed under the agent provider's settings and policies. Run shared Stove servers only within a trusted network, as described in the [MCP guide](https://trendyol.github.io/stove/Components/21-mcp/).

## License

Apache-2.0. See [LICENSE](LICENSE).
