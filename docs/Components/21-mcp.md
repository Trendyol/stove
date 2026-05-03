# MCP

Stove CLI exposes a local MCP endpoint for AI agents. It lets agents inspect failed end-to-end tests through compact, structured tools instead of loading full logs into context.

MCP starts automatically when you run `stove`:

```bash
stove
```

The startup output includes the endpoint:

```text
Stove CLI v0.23.0 running
UI:   http://localhost:4040
REST: http://localhost:4040/api/v1
MCP:  http://localhost:4040/mcp
gRPC: localhost:4041
```

## Discovery

Agents and humans can discover Stove MCP from:

- the `stove` startup banner
- the dashboard UI
- `GET http://localhost:4040/api/v1/meta`
- project or agent instructions

The metadata endpoint includes:

```json
{
  "stove_cli_version": "0.23.0",
  "mcp": {
    "enabled": true,
    "transport": "streamable-http",
    "endpoint": "http://localhost:4040/mcp",
    "scope": "read-only-test-observability"
  }
}
```

Most MCP clients need the endpoint URL explicitly. There is no guaranteed universal auto-discovery mechanism for local MCP servers, so the endpoint is advertised in the places above.

## Integration

Stove MCP is served by `stove-cli`; application systems and test JVMs do not start or host MCP themselves. The integration path is:

1. Start `stove`.
2. Configure the MCP client or agent runtime to use the Streamable HTTP endpoint from the startup banner or `/api/v1/meta`.
3. Run the tests as usual. Stove records runs, entries, traces, and snapshots through its normal dashboard pipeline.
4. Let the agent query MCP for compact evidence after a failure.

Generic MCP client configuration should point at the same HTTP port as the dashboard:

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

Exact configuration keys vary by agent runtime, but the important values are the transport and URL. If the dashboard runs on a custom port, use the endpoint printed by `stove` or returned from `/api/v1/meta`.

Recommended agent instruction:

```text
When Stove is running, prefer the local Stove MCP endpoint for failed-test triage.
Start with stove_failures, then use the returned run_id + test_id with
stove_failure_detail. Drill into stove_timeline, stove_trace, or stove_snapshot
only when needed. If MCP is unavailable, ambiguous, or incomplete, fall back to
normal test output, Stove reports, and logs.
```

Agents should not infer a test selector from names alone. A database can contain multiple apps, multiple runs per app, and duplicate test names. Use the exact `run_id + test_id` returned by MCP.

## Agent Workflow

Use MCP as an optimization, not as a dependency:

1. Call `stove_failures`.
2. Pick a specific `run_id` and `test_id` from the result.
3. Call `stove_failure_detail` for the compact failure packet.
4. Drill into `stove_timeline`, `stove_trace`, or `stove_snapshot` only when needed.
5. If MCP is unavailable, ambiguous, or missing data, fall back to normal test output, Stove failure reports, and logs.

Stove data is hierarchical:

```text
database
  -> apps by app_name
    -> runs by run_id
      -> tests by test_id
        -> entries, spans, snapshots
```

`app_name` is a grouping label. A single database can contain multiple apps, and each app can have many runs. `run_id + test_id` is the exact test selector.

## Tools

| Tool | Purpose |
|------|---------|
| `stove_apps` | Lists apps recorded in the dashboard database |
| `stove_runs` | Lists runs, filterable by app and status |
| `stove_failures` | Default entrypoint; failed tests grouped by app and run |
| `stove_failure_detail` | Compact detail for one exact failed test |
| `stove_timeline` | Ordered test actions, failure-focused by default |
| `stove_trace` | Critical path and exception evidence from correlated spans |
| `stove_snapshot` | System snapshot summaries and targeted JSON drill-down |
| `stove_raw_evidence` | Capped raw lookup for one entry, span, or snapshot |

Every failure result includes ready-to-use next tool calls, so agents do not need to guess scopes from names.

## Token Budgeting

MCP tools default to compact output. Large payloads are truncated deterministically and include omitted counts or follow-up tool calls. Sensitive keys such as `authorization`, `cookie`, `password`, `secret`, `token`, `apiKey`, and `credential` are redacted before data is returned.

Use `budget` when a client needs a different amount of detail:

```json
{
  "budget": "tiny"
}
```

Supported values are `tiny`, `compact`, and `full`. Tools that expose raw evidence also accept `max_chars`.

## Security

The MCP endpoint is read-only and local-only. It does not expose tools to clear data, retry tests, delete runs, or mutate snapshots.

`/mcp` accepts loopback clients and localhost `Host`/`Origin` headers. Requests from non-local hosts are rejected to reduce the risk of browser or DNS rebinding abuse.

## Troubleshooting

If an agent cannot use MCP:

- confirm `stove` is running
- check the startup banner for the actual port
- open `http://localhost:4040/api/v1/meta` and verify `mcp.enabled` is `true`
- make sure the MCP client is configured with `http://localhost:4040/mcp`
- fall back to normal test output and logs if the endpoint cannot be reached

If MCP returns no failures, the latest recorded runs may have passed, the dashboard dependency may not be registered in the test config, or the test run may still be in progress.
