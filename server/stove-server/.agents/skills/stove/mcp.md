# Stove MCP — Agent Triage

The Stove Server exposes a read-only **Model Context Protocol** endpoint at `/mcp`. Agents use it to inspect end-to-end test runs through compact, structured tools instead of loading raw logs into context. It works locally at `http://localhost:4040/mcp` or against a shared internal Stove server.

Use MCP as an optimization, not a dependency. If MCP is unavailable, fall back to normal test output, Stove failure reports, and logs.

## When to use this skill

- The user is testing with Stove and a recent run has failures
- The user mentions "MCP", "stove failures", or asks for triage of a Stove run
- An agent task instruction says to prefer a Stove MCP endpoint

## Discovery

When `stove` is running, the startup banner prints the endpoint:

```text
Stove Server v0.26.0 running
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
  "stove_server_version": "0.26.0",
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

For a shared deployment, replace `localhost` with the internal server name:

```json
{
  "mcpServers": {
    "stove": {
      "type": "http",
      "url": "http://stove.internal:4040/mcp"
    }
  }
}
```

## Agent workflow

For a local server with one relevant run, call `stove_failures` first. For a shared server, do not survey unrelated failures:

1. Call `stove_runs` with `app_name` and the metadata supplied by the CI job, such as project, pipeline, and team.
2. Pick the exact `run_id` from the result. Metadata is supported by `stove_runs`, not directly by `stove_failures`.
3. Call `stove_failures(run_id=...)`, then pick a `test_id`. **Never infer a selector from names alone** — multiple apps and runs can contain duplicate test names.
4. Call `stove_failure_detail` with that exact `run_id + test_id` for the compact failure packet.
5. Drill into `stove_timeline`, `stove_trace`, `stove_snapshot`, or `stove_interactions` only when needed. For "why did the mock not match" questions, the near-miss diagnoses are already in `stove_failure_detail`'s `unmatched_interactions`.
6. Use `stove_raw_evidence` for one specific entry, span, snapshot, interaction, or warning when the compact view is not enough.
7. If MCP is missing data, fall back to normal test output and logs.

Every failure result includes ready-to-use next tool calls — use them, don't guess.

### Selecting a shared-server run

`stove_runs.metadata` accepts dynamic string key/value pairs. Matching is exact, every supplied pair is AND-combined, and only retained runs can be returned:

```json
{
  "app_name": "checkout-api",
  "status": "FAILED",
  "metadata": {
    "team": "checkout",
    "gitlab.project": "commerce/checkout-api",
    "gitlab.pipeline_id": "12345"
  }
}
```

Then query the selected execution:

```text
stove_failures(run_id="<returned-run-id>")
stove_failure_detail(run_id="<returned-run-id>", test_id="<returned-test-id>")
```

Metadata originates in `DashboardSystemOptions(metadata = mapOf(...))`; see [dashboard.md](dashboard.md). Do not invent metadata values or silently broaden a failed lookup. Ask for the current CI dimensions or use `stove_runs` without metadata only when surveying all retained runs is intended.

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

The same data is on REST for the UI through explicitly named resources: `/api/v1/runs/{run_id}/mock-interactions` and `/api/v1/runs/{run_id}/mock-warnings`, with corresponding per-test and `/ambient` variants.

Interactions with no `test_id` are unattributed by design (attribution is proven-only — header, baggage, or matched-stub tag; never inferred). Do not guess an owner for them from timing or names. Snapshots carry a `trigger` (`TEST_END` or `FAILURE`); the `FAILURE` one is the state at the moment the first failing entry was recorded.

## Tools

| Tool | Purpose |
|------|---------|
| `stove_apps` | Apps recorded in the dashboard database |
| `stove_runs` | Runs, filterable by app, status, and exact metadata subset |
| `stove_failures` | Failed tests grouped by app and run; accepts an exact `run_id`, but not metadata |
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
- **No authentication or authorization**: remote clients and non-local `Host`/`Origin` headers are accepted.
- **Trusted networks only**: HTTP, MCP, administration, and gRPC ingestion are reachable on all interfaces. Use a firewall, private ingress, or equivalent external boundary; never expose Stove directly to an untrusted network.
- MCP cannot purge or change retention. Those mutations are available only through the dashboard Admin page and REST API.

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
When Stove is running, prefer its MCP endpoint for failed-test triage. On a
shared server, first call stove_runs with the CI-provided app_name and metadata,
then call stove_failures with the returned run_id. Use the exact run_id + test_id
with stove_failure_detail, and drill into timeline, trace, snapshot, or
interactions only when needed. If MCP is unavailable, ambiguous, or incomplete,
fall back to normal test output, Stove reports, and logs.
```

## Reference

- Component docs: `docs/Components/21-mcp.md`
- Dashboard component (data source): `docs/Components/18-dashboard.md`
