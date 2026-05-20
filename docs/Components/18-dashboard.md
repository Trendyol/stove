# Dashboard

A local web UI for evidence emitted by registered Stove systems. Timelines, span trees, snapshots, and Kafka explorer views live in a SQLite database so runs persist across sessions. Live updates stream via SSE.

<div class="stove-tldr" markdown>
<span class="stove-tldr-title">In 30 seconds</span>
Install <code>stove-cli</code>, run <code>stove</code>, add <code>dashboard { }</code> in <code>Stove().with</code>, then open <code>http://localhost:4040</code>. The dashboard stays empty until tests stream events to the CLI.
</div>

Current CLI versions start the dashboard with bare `stove`; older docs and scripts may still show `stove serve`.

## Preview

The dashboard is useful because the test timeline, trace tree, and system evidence stay linked to the same run instead of living in separate tools.

{{ dashboard_preview() }}

## Install the CLI

=== "Homebrew"

    ```bash
    brew tap trendyol/tap
    brew install stove
    ```

=== "curl"

    ```bash
    curl -fsSL https://raw.githubusercontent.com/Trendyol/stove/main/tools/stove-cli/install.sh | bash
    ```

=== "Manual"

    Download the right binary from [releases](https://github.com/Trendyol/stove/releases) and add to `$PATH`.

Verify: `stove --version`.

## Start the dashboard

```bash
stove                                  # default UI/REST/MCP port 4040, gRPC port 4041
stove --port 9000 --grpc-port 9001     # override ports
stove --fresh-start                    # back up and recreate the DB, then start
stove --db ./my-stove.sqlite           # custom DB path
stove --clear                          # clear stored runs and exit
```

Open the printed URL. Empty until tests run.

## Wire your tests

```kotlin
Stove().with {
    dashboard {
        DashboardSystemOptions(appName = "my-service")
        // If you start the CLI with `stove --grpc-port 9001`, match that here:
        // DashboardSystemOptions(appName = "my-service", cliHost = "localhost", cliPort = 9001)
    }
    // ... other systems + runner
}.run()
```

Now the registered Stove systems stream test events to the dashboard while the CLI is running.

## What you see

<div class="stove-catalog">
  <div class="stove-sys-card">
    <div class="stove-sys-card-head"><strong>Timeline</strong><span class="stove-sys-card-badge">per test</span></div>
    <p class="stove-sys-card-desc">Chronological list of every HTTP call, DB op, Kafka publish, WireMock match, gRPC call. Click any entry to see request/response payloads.</p>
  </div>
  <div class="stove-sys-card">
    <div class="stove-sys-card-head"><strong>Trace</strong><span class="stove-sys-card-badge">OTel</span></div>
    <p class="stove-sys-card-desc">Interactive span tree with attribute search. Requires <a href="../15-tracing/">Tracing</a> enabled.</p>
  </div>
  <div class="stove-sys-card">
    <div class="stove-sys-card-head"><strong>Snapshots</strong><span class="stove-sys-card-badge">at failure</span></div>
    <p class="stove-sys-card-desc">System state captured when an assertion failed. WireMock unmatched, Kafka topics, DB rows.</p>
  </div>
  <div class="stove-sys-card">
    <div class="stove-sys-card-head"><strong>Kafka Explorer</strong><span class="stove-sys-card-badge">live</span></div>
    <p class="stove-sys-card-desc">All published + consumed messages. Filter by topic, partition, headers. Drill into payloads.</p>
  </div>
</div>

## Data model

```
database
└── apps (one per appName)
    └── runs (one per test suite execution)
        └── tests (one per test case)
            ├── entries  (HTTP, DB, Kafka, ...)
            ├── spans    (OTel tree)
            └── snapshots (system state at failure)
```

Runs persist until you clear or recreate the database. Browse old runs to compare regressions.

## Fault tolerance

Dashboard is **opt-in** and **non-blocking**:

- Events queue locally; gRPC publish happens in the background.
- If the CLI is down or unreachable, the gRPC client auto-disables for the rest of the suite. Tests continue. No flakes.
- Tests never wait on the dashboard.

## REST API

The CLI exposes REST endpoints for integration:

| Endpoint | Use |
|---|---|
| `GET /api/v1/meta` | discovery; version, capabilities, MCP availability |
| `GET /api/v1/apps` | list registered apps |
| `GET /api/v1/runs?app=...` | list runs for an app |
| `GET /api/v1/runs/{run}/tests` | tests in a run |
| `GET /api/v1/traces/{trace_id}` | span tree |
| `GET /api/v1/events/stream` | SSE: live test events |

Useful for CI artifact extraction, custom analyzers, or building tooling on top.

## CLI options reference

| Flag | Default | Notes |
|---|---|---|
| `--port` | 4040 | web UI, REST, and MCP |
| `--grpc-port` | 4041 | event ingestion from Stove tests |
| `--db` | `~/.stove-dashboard.db` | persistence path |
| `--clear` | off | clear stored runs and exit |
| `--fresh-start` | off | back up and recreate the DB before serving |

## Pairs well with

<div class="grid cards" markdown>

-   :material-chart-timeline-variant: **[Tracing](15-tracing.md)**. Span tree shows up in Trace view.

-   :material-robot-outline: **[MCP](21-mcp.md)**. Same database, agent-readable.

-   :material-text-box-search-outline: **[Reporting](13-reporting.md)**. Console reports plus dashboard history cover complementary debugging surfaces.

-   :material-chart-arc: **[When a test fails](../observability/when-it-fails.md)**. Dashboard is step 3 of the failure flow.

</div>

## Troubleshooting

| Symptom | Check |
|---|---|
| Dashboard empty | `stove` running? `dashboard { }` registered in `Stove().with`? `appName` set? |
| Events not arriving | Port mismatch. `cliPort` in `DashboardSystemOptions` must match `--grpc-port` |
| "gRPC disabled" warning | Expected if CLI started after tests; restart in correct order |
| Disk filling up | `~/.stove-dashboard.db` grows with runs; periodically run `stove --clear` |
