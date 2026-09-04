# MCP

`stove-server` exposes a Model Context Protocol endpoint. AI agents (Claude Code, Cursor, ...) query failed runs through compact, structured tools instead of grepping raw logs.

<div class="stove-tldr" markdown>
<span class="stove-tldr-title">In 30 seconds</span>
Start <code>stove</code>. Point your agent at the MCP endpoint. Agents call <code>stove_failures</code>, <code>stove_failure_detail</code>, <code>stove_trace</code>, <code>stove_snapshot</code> against the same database the <a href="../18-dashboard/">Dashboard</a> reads. Token-aware and read-only.
</div>

## Discovery

Banner on `stove` shows the endpoint:

```
stove

Stove Server v0.26.0 running
UI:   http://localhost:4040
REST: http://localhost:4040/api/v1
MCP:  http://localhost:4040/mcp
gRPC: localhost:4041
```

Or hit `GET /api/v1/meta` for metadata:

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

## Connect an agent

=== "Claude Code"

    ```json
    // ~/.config/claude-code/config.json
    {
      "mcpServers": {
        "stove": {
          "type": "http",
          "url": "http://localhost:4040/mcp"
        }
      }
    }
    ```

=== "Cursor / Continue / ..."

    Use the standard Streamable HTTP MCP transport. The URL is `http://localhost:4040/mcp`; the config key may be named `type` or `transport` depending on the client.

For a shared deployment, use the server's internal address instead, for example `http://stove.internal:4040/mcp`.

## Tools

| Tool | Returns |
|---|---|
| `stove_apps` | apps recorded in the dashboard database |
| `stove_runs` | runs, filterable by app, status, and dynamic metadata key/value pairs |
| `stove_failures` | top-N recent failures across all apps/runs, summarized |
| `stove_failure_detail` | one failure: assertion, system entries, snapshot summary |
| `stove_timeline` | chronological events for one test |
| `stove_trace` | OTel span tree for one test (when [tracing](15-tracing.md) is on) |
| `stove_snapshot` | system state at failure (Kafka topics, WireMock unmatched, ...) |
| `stove_interactions` | mock exchanges and warnings for a test or whole run, including unattributed evidence |
| `stove_raw_evidence` | full untruncated entry / payload (rarely needed) |

## Data model

```
database
└── apps           (one per appName)
    └── runs       (one per test suite execution)
        └── tests  (one per test case)
            ├── entries        HTTP/DB/Kafka/...
            ├── spans          OTel tree
            ├── snapshots      system state at failure
            └── interactions   WireMock/gRPC Mock exchanges and warnings
```

Tools use `app_name`, `run_id`, or `test_id` to drill down. For one local run, start with `stove_failures`. On a shared server, select the run by metadata first.

When multiple GitLab jobs or teams publish to one Stove server, use `stove_runs.metadata` to select an exact metadata subset. The keys are dynamic and all values are strings:

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

All supplied key/value pairs must match exactly and only retained runs are searchable. `stove_failures` does not accept metadata directly: pass a returned `run_id` to it, then use that same run ID with the failure, timeline, trace, snapshot, interaction, and raw-evidence tools.

## Token budgeting

Each tool ships in three modes:

- **`tiny`**. Top-line summary only. Use for surveys.
- **`compact`** (default). Most decision-grade detail; truncated payloads.
- **`full`**. Untruncated. Costs tokens; only when needed.

Sensitive keys are auto-redacted (passwords, JWTs, common secret patterns).

## Recommended agent workflows

For a local server with one relevant run:

```
1. stove_failures(limit=5, app_name="my-service")
   → list of recent failures, with test_id and run_id

2. stove_failure_detail(test_id, run_id, budget="compact")
   → assertion, entries leading up to it, snapshot summary

3. (optional) stove_trace(test_id, run_id)
   → call chain inside the app

4. (optional) stove_snapshot(test_id, run_id, system="kafka")
   → drill into one system if root cause unclear
```

For a shared server receiving several teams or CI jobs:

```
1. stove_runs(
       app_name="checkout-api",
       status="FAILED",
       metadata={"gitlab.project":"commerce/checkout-api", "gitlab.pipeline_id":"12345"}
   )
   → select the exact run_id

2. stove_failures(run_id="...")
   → select the exact test_id

3. stove_failure_detail(run_id="...", test_id="...", budget="compact")
   → focused failure packet for that CI run

4. (optional) stove_timeline / stove_trace / stove_snapshot / stove_interactions
   → query more evidence with the same run_id and test_id
```

Do not silently remove metadata after an empty result; confirm the key/value pairs produced by the CI job. The metadata is configured by the test suite's [`DashboardSystemOptions`](18-dashboard.md#wire-your-tests).

## Security

- **Read-only.** No mutations. No exec. No file writes.
- **No outbound calls.** Agent reads what `stove` already stored.
- **No authentication or authorization.** Remote clients and non-local `Host` / `Origin` headers are accepted so agents can query a shared internal server. Expose Stove only on a trusted network and enforce any network boundary outside Stove.

## Pairs well with

<div class="grid cards" markdown>

-   :material-monitor-dashboard: **[Dashboard](18-dashboard.md)**. Same data, human-readable view.

-   :material-chart-timeline-variant: **[Tracing](15-tracing.md)**. `stove_trace` only works when tracing is enabled.

-   :material-text-box-search-outline: **[Reporting](13-reporting.md)**. `entries` come from the reporter.

-   :material-chart-arc: **[When a test fails](../observability/when-it-fails.md)**. Step 5 shows MCP in action.

</div>

## Troubleshooting

| Symptom | Check |
|---|---|
| Agent can't connect | `stove` running? Port matches MCP URL? |
| `stove_failures` empty | Tests producing events? `dashboard { }` registered in `Stove().with`? |
| `stove_trace` returns nothing | Tracing enabled? See [Tracing setup](15-tracing.md) |
| Payloads truncated | Use `budget="full"` for full detail (token cost) |
