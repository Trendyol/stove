# MCP

`stove-cli` exposes a local Model Context Protocol endpoint. AI agents (Claude Code, Cursor, ...) query failed runs through compact, structured tools instead of grepping raw logs.

<div class="stove-tldr" markdown>
<span class="stove-tldr-title">In 30 seconds</span>
Start <code>stove serve</code>. Point your agent at the MCP endpoint. Agents call <code>stove_failures</code>, <code>stove_failure_detail</code>, <code>stove_trace</code>, <code>stove_snapshot</code> against the same SQLite the <a href="../18-dashboard/">Dashboard</a> reads. Token-aware, read-only, loopback-only.
</div>

## Discovery

Banner on `stove serve` shows the endpoint:

```
stove serve
─────────────────────────────────────
 Dashboard:  http://localhost:8086
 MCP:        http://localhost:8086/mcp
 SQLite:     ~/.stove/dashboard.sqlite
─────────────────────────────────────
```

Or hit `GET /api/v1/meta` for metadata:

```json
{
  "version": "0.24.0",
  "mcp": { "enabled": true, "path": "/mcp" }
}
```

## Connect an agent

=== "Claude Code"

    ```json
    // ~/.config/claude-code/config.json
    {
      "mcpServers": {
        "stove": {
          "url": "http://localhost:8086/mcp",
          "transport": "http"
        }
      }
    }
    ```

=== "Cursor / Continue / ..."

    Use the standard MCP HTTP transport. URL = `http://localhost:8086/mcp`.

## Tools

| Tool | Returns |
|---|---|
| `stove_failures` | top-N recent failures across all apps/runs, summarized |
| `stove_failure_detail` | one failure: assertion, system entries, snapshot summary |
| `stove_timeline` | chronological events for one test |
| `stove_trace` | OTel span tree for one test (when [tracing](15-tracing.md) is on) |
| `stove_snapshot` | system state at failure (Kafka topics, WireMock unmatched, ...) |
| `stove_raw_evidence` | full untruncated entry / payload (rarely needed) |

## Data model

```
database
└── apps           (one per appName)
    └── runs       (one per test suite execution)
        └── tests  (one per test case)
            ├── entries     HTTP/DB/Kafka/...
            ├── spans       OTel tree
            └── snapshots   system state at failure
```

Most tools take `app`, `run_id`, or `test_id` to drill down. Start broad with `stove_failures`, then narrow.

## Token budgeting

Each tool ships in three modes:

- **`tiny`**. Top-line summary only. Use for surveys.
- **`compact`** (default). Most decision-grade detail; truncated payloads.
- **`full`**. Untruncated. Costs tokens; only when needed.

Sensitive keys are auto-redacted (passwords, JWTs, common secret patterns).

## Recommended agent workflow

```
1. stove_failures(limit=5, app="my-service")
   → list of recent failures, with test_id and run_id

2. stove_failure_detail(test_id, run_id, mode="compact")
   → assertion, entries leading up to it, snapshot summary

3. (optional) stove_trace(test_id, run_id)
   → call chain inside the app

4. (optional) stove_snapshot(test_id, run_id, system="kafka")
   → drill into one system if root cause unclear
```

## Security

- **Loopback-only.** Bound to `127.0.0.1` by default. Override with `--mcp-host`.
- **Read-only.** No mutations. No exec. No file writes.
- **No outbound calls.** Agent reads what `stove serve` already stored.

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
| Agent can't connect | `stove serve` running? Port matches MCP URL? |
| `stove_failures` empty | Tests producing events? `dashboard { }` registered in `Stove().with`? |
| `stove_trace` returns nothing | Tracing enabled? See [Tracing setup](15-tracing.md) |
| Payloads truncated | Use `mode="full"` for full detail (token cost) |
