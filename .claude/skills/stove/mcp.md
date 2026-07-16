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

## MCP client config

Claude Code uses `type = "http"` for Streamable HTTP MCP servers:

```json
{
  "mcpServers": {
    "stove": {
      "type": "http",
      "url": "http://localhost:4040/mcp"
    }
  }
}
```

Some clients call the same field `transport` and may accept `streamable-http`. The endpoint URL is the load-bearing value.

## Agent Workflow (the only correct order)

1. Call `stove_failures` first.
2. Pick a specific `run_id` and `test_id` from the result. **Never infer a test selector from names alone** — multiple apps and runs can contain duplicate test names.
3. Call `stove_failure_detail` with that exact `run_id + test_id` for the compact failure packet.
4. Drill into `stove_timeline`, `stove_trace`, `stove_snapshot`, or `stove_interactions` only when needed. For "why did the mock not match" questions, the near-miss diagnoses are already in `stove_failure_detail`'s `unmatched_interactions`.
5. Use `stove_raw_evidence` for one specific entry / span / snapshot / interaction / warning when the compact view isn't enough.
6. If MCP is missing data, fall back to normal test output and logs.

Every failure result includes ready-to-use next tool calls — use them, don't guess.

## Data hierarchy

```
database
  -> apps by app_name
    -> runs by run_id
      -> tests by test_id
        -> entries, spans, snapshots, mock interactions, mock warnings
      -> unattributed mock interactions / warnings (run-level "ambient" lane)
```

`app_name` is the label set in `DashboardSystemOptions(appName = "...")` on the test side. `run_id + test_id` is the only authoritative selector.

Since 0.26, every request that reaches a WireMock or gRPC Mock is recorded as a **mock interaction** (matched or not, with status, latency, near-miss diagnoses, and proven-only attribution), and the mocks raise **warnings** (`UNUSED_STUB`, `CROSS_TEST_MATCH`, `UNVALIDATED_UNMATCHED`). Agents get them through MCP:

- `stove_failure_detail` includes the failed test's `unmatched_interactions` (each carrying its near-miss diagnoses — usually *the* answer to "why did nothing match") and `mock_warnings`.
- `stove_timeline` interleaves mock exchanges with report entries chronologically; events are tagged `"type": "entry" | "mock_interaction"`.
- `stove_interactions` lists exchanges and warnings for one test (`run_id + test_id`) or a whole run (omit `test_id`), the run scope including the unattributed lane.
- `stove_raw_evidence` accepts `kind: "interaction"` and `kind: "warning"` with `run_id + id`.

The same data is on REST for the UI: `/api/v1/runs/{run_id}/interactions` (+ `/ambient`, per-test, and `warnings` variants).

Interactions with no `test_id` are unattributed by design (attribution is proven-only — header, baggage, or matched-stub tag; never inferred). Do not guess an owner for them from timing or names. Snapshots carry a `trigger` (`TEST_END` or `FAILURE`); the `FAILURE` one is the state at the moment the first failing entry was recorded.

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
| `stove_interactions` | Mock exchanges + warnings for a test or whole run, incl. the unattributed lane |
| `stove_raw_evidence` | Capped raw lookup for one entry, span, snapshot, interaction, or warning |

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
