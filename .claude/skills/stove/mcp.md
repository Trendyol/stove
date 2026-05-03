# Stove MCP — Agent Triage

The Stove CLI exposes a local **Model Context Protocol** endpoint at `http://localhost:4040/mcp`. Agents use it to inspect failed end-to-end tests through compact, structured tools instead of loading raw logs into context.

Use MCP as an optimization, not a dependency. If MCP is unavailable, fall back to normal test output, Stove failure reports, and logs.

## When to use this skill

- The user is testing with Stove and a recent run has failures
- The user mentions "MCP", "stove failures", or asks for triage of a Stove run
- An agent task instruction says to prefer the local Stove MCP endpoint

## Discovery

When `stove` is running, the startup banner prints the endpoint:

```text
Stove CLI v0.24.0 running
UI:   http://localhost:4040
REST: http://localhost:4040/api/v1
MCP:  http://localhost:4040/mcp
gRPC: localhost:4041
```

Or query metadata:

```bash
curl -s http://localhost:4040/api/v1/meta
```

```json
{
  "stove_cli_version": "0.24.0",
  "mcp": {
    "enabled": true,
    "transport": "streamable-http",
    "endpoint": "http://localhost:4040/mcp",
    "scope": "read-only-test-observability"
  }
}
```

## MCP client config (generic)

```json
{
  "mcpServers": {
    "stove": {
      "transport": "streamable-http",
      "url": "http://localhost:4040/mcp"
    }
  }
}
```

Exact keys vary by agent runtime. The endpoint URL is the load-bearing value.

## Agent Workflow (the only correct order)

1. Call `stove_failures` first.
2. Pick a specific `run_id` and `test_id` from the result. **Never infer a test selector from names alone** — multiple apps and runs can contain duplicate test names.
3. Call `stove_failure_detail` with that exact `run_id + test_id` for the compact failure packet.
4. Drill into `stove_timeline`, `stove_trace`, or `stove_snapshot` only when needed.
5. Use `stove_raw_evidence` for one specific entry / span / snapshot when the compact view isn't enough.
6. If MCP is missing data, fall back to normal test output and logs.

Every failure result includes ready-to-use next tool calls — use them, don't guess.

## Data hierarchy

```
database
  -> apps by app_name
    -> runs by run_id
      -> tests by test_id
        -> entries, spans, snapshots
```

`app_name` is the label set in `DashboardSystemOptions(appName = "...")` on the test side. `run_id + test_id` is the only authoritative selector.

## Tools

| Tool | Purpose |
|------|---------|
| `stove_apps` | Apps recorded in the dashboard database |
| `stove_runs` | Runs, filterable by app and status |
| `stove_failures` | Default entrypoint — failed tests grouped by app and run |
| `stove_failure_detail` | Compact detail for one exact failed test |
| `stove_timeline` | Ordered test actions, failure-focused by default |
| `stove_trace` | Critical path and exception evidence from correlated spans |
| `stove_snapshot` | System snapshot summaries with targeted JSON drill-down |
| `stove_raw_evidence` | Capped raw lookup for one entry, span, or snapshot |

## Token Budgeting

Tools default to compact output. Large payloads are truncated deterministically and include omitted counts or follow-up tool calls. Sensitive keys (`authorization`, `cookie`, `password`, `secret`, `token`, `apiKey`, `credential`) are redacted before return.

Use `budget` to dial detail:

```json
{ "budget": "tiny" }   // tiny | compact | full
```

Tools that expose raw evidence also accept `max_chars`.

## Security

- **Read-only**: no tools to clear data, retry tests, delete runs, or mutate snapshots.
- **Local-only**: `/mcp` accepts loopback clients and localhost `Host`/`Origin` headers. Non-local hosts are rejected (mitigates DNS rebinding).
- Safe to run on a dev machine; do not expose externally.

## Troubleshooting

If MCP is unreachable:

- confirm `stove` is running (`brew install Trendyol/trendyol-tap/stove` then `stove`)
- check the startup banner for the actual port (some installs use a custom one)
- open `http://localhost:4040/api/v1/meta` and verify `mcp.enabled` is `true`
- make sure the agent runtime is configured with `http://localhost:4040/mcp`
- fall back to normal test output and logs if the endpoint cannot be reached

If MCP returns no failures:

- the latest recorded runs may have passed
- the test config may not register `stove-dashboard` (no data is being recorded)
- the test run may still be in progress

## Recommended agent instruction

Add to your project's agent rules / system prompt:

```text
When Stove is running, prefer the local Stove MCP endpoint for failed-test triage.
Start with stove_failures, then use the returned run_id + test_id with
stove_failure_detail. Drill into stove_timeline, stove_trace, or stove_snapshot
only when needed. If MCP is unavailable, ambiguous, or incomplete, fall back to
normal test output, Stove reports, and logs.
```

## Reference

- Component docs: `docs/Components/21-mcp.md`
- Dashboard component (data source): `docs/Components/18-dashboard.md`
