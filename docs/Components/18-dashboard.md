# Dashboard

A web UI for evidence emitted by registered Stove systems. Timelines, span trees, snapshots, and Kafka explorer views live in SQLite by default, or in PostgreSQL for a shared deployment. Live updates stream via SSE.

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
    brew install trendyol/trendyol-tap/stove
    ```

=== "curl"

    ```bash
    curl -fsSL https://raw.githubusercontent.com/Trendyol/stove/main/tools/stove-cli/install.sh | bash
    ```

=== "Manual"

    Download the right binary from [releases](https://github.com/Trendyol/stove/releases) and add to `$PATH`.

Verify: `stove --version`.

Upgrade an existing install (Homebrew caches the tap, so refresh it first):

```bash
brew update           # refresh the tap manifests so new versions are visible
brew upgrade stove
```

### Dev channel (`stove-next`)

Want the latest changes before a release? Every Maven snapshot publish also ships the CLI as a rolling dev channel, versioned to match the snapshot (e.g. `1.0.0.57-SNAPSHOT`):

```bash
brew install trendyol/trendyol-tap/stove-next
```

Both formulae install the same `stove` binary, so they conflict — switch channels by uninstalling one first:

```bash
brew uninstall stove && brew install trendyol/trendyol-tap/stove-next   # stable → next
brew uninstall stove-next && brew install trendyol/trendyol-tap/stove   # next → stable
```

New snapshots replace the channel in place; `brew update && brew upgrade stove-next` moves you to the latest. Older snapshot binaries are not kept — if you need a version that stays put, use the stable `stove` formula.

## Start the dashboard

```bash
stove                                  # default UI/REST/MCP port 4040, gRPC port 4041
stove --port 9000 --grpc-port 9001     # override ports
stove --retention-runs-per-app 50      # keep 50 completed runs per app (default: 1; 0: unlimited)
stove --fresh-start                    # back up and recreate the DB, then start
stove --db ./my-stove.sqlite           # custom DB path
stove --database-url postgresql://stove:secret@db.example/stove
stove --clear                          # clear stored runs and exit
```

Open the printed URL. Empty until tests run.

## Wire your tests

```kotlin
Stove().with {
    dashboard {
        DashboardSystemOptions(
            appName = "checkout-api",
            metadata = mapOf(
                "team" to "checkout",
                "tribe" to "commerce",
                "gitlab.project" to (System.getenv("CI_PROJECT_PATH") ?: "local"),
                "gitlab.pipeline_id" to (System.getenv("CI_PIPELINE_ID") ?: "local")
            )
        )
        // If you start the CLI with `stove --grpc-port 9001`, match that here:
        // DashboardSystemOptions(appName = "my-service", cliHost = "localhost", cliPort = 9001)
    }
    // ... other systems + runner
}.run()
```

Now the registered Stove systems stream test events to the dashboard while the CLI is running.

Metadata is an immutable set of string key/value pairs attached when a run starts. Keys are intentionally open-ended, so CI jobs can describe a run with team, project, pipeline, branch, environment, or any other useful dimensions without a server schema change.

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

The sidebar can switch between retained runs and filter them by any number of metadata key/value pairs. Keys and values are selectable from those present in the application's retained runs, so users do not have to type CI identifiers manually. Every pair uses exact string matching and all supplied pairs must match.

By default, Stove retains the newest completed run for each application, preserving the previous local behavior. Set `--retention-runs-per-app N` or `STOVE_RETENTION_RUNS_PER_APP=N` to keep more; `0` keeps unlimited history. Running runs are not removed by automatic retention.

## Storage backends

SQLite remains the zero-configuration default and stores data at `~/.stove-dashboard.db`. For a server shared by CI jobs, teams, and agents, configure PostgreSQL with either form:

```bash
stove --database-url 'postgresql://stove:secret@db.example/stove'
STOVE_DATABASE_URL='postgresql://stove:secret@db.example/stove' stove
```

PostgreSQL connections use TLS by default. Add `?sslmode=disable` only for a trusted PostgreSQL endpoint that intentionally has no TLS. Stove applies versioned migrations at startup. Backend-specific migrations live in parallel `tools/stove-cli/src/storage/migrations/sqlite/` and `tools/stove-cli/src/storage/migrations/postgres/` directories. Run metadata is stored as `JSONB` with a GIN `jsonb_path_ops` index, so dynamic exact-subset filters remain efficient without predeclaring keys.

`--db` and `--fresh-start` are SQLite-only; Stove rejects `--fresh-start` when PostgreSQL is selected. `--clear` operates on the selected backend.

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
| `GET /api/v1/runs?app=...&metadata=...` | list runs by app and/or an exact metadata subset |
| `GET /api/v1/runs/{run}/tests` | tests in a run |
| `GET /api/v1/traces/{trace_id}` | span tree |
| `GET /api/v1/events/stream` | SSE: live test events |

Useful for CI artifact extraction, custom analyzers, or building tooling on top.

The `metadata` query value is a URL-encoded JSON object whose values must be strings. Every supplied pair must match:

```bash
curl --get 'http://localhost:4040/api/v1/runs' \
  --data-urlencode 'app=checkout-api' \
  --data-urlencode 'metadata={"team":"checkout","gitlab.pipeline_id":"12345"}'
```

Agents can apply the same dynamic filter through `stove_runs`:

```json
{
  "app_name": "checkout-api",
  "metadata": {
    "team": "checkout",
    "gitlab.pipeline_id": "12345"
  }
}
```

## Administration

Select **Admin** in the dashboard header to open the dedicated `/admin` page, where you can inspect storage, change runtime retention, preview and purge matching runs, or clear all data. Destructive operations require confirmation. A retention change prunes excess completed runs immediately; it lasts for the current process, so set the CLI flag or environment variable for the value to survive a restart.

Purge preview accepts an optional application and RFC 3339 `older_than` cutoff, then returns the exact run IDs and evidence counts. Purging uses those IDs. Both preview and purge exclude active runs unless `include_running` is explicitly `true`.

| Endpoint | Use |
|---|---|
| `GET /api/v1/admin/status` | backend, retention, run, and evidence counts |
| `PUT /api/v1/admin/retention` | set runtime retention with `{"runs_per_app": 50}` |
| `POST /api/v1/admin/purge/preview` | preview with `app_name`, `older_than`, and `include_running` |
| `POST /api/v1/admin/purge` | delete exact `run_ids`; active runs still require `include_running` |
| `DELETE /api/v1/data` | clear every run and all evidence |

!!! warning "Trusted networks only"
    The dashboard, REST API, admin operations, gRPC ingestion, and MCP endpoint intentionally have no authentication or authorization. The servers listen on all interfaces so remote CI jobs and agents can connect. Deploy Stove only on an internal trusted network and control exposure outside Stove (for example with firewall rules or a private ingress).

## CLI options reference

| Flag | Default | Notes |
|---|---|---|
| `--port` | 4040 | web UI, REST, and MCP |
| `--grpc-port` | 4041 | event ingestion from Stove tests |
| `--db` | `~/.stove-dashboard.db` | persistence path |
| `--database-url` | unset | PostgreSQL URL; replaces SQLite. Also configurable with `STOVE_DATABASE_URL` |
| `--retention-runs-per-app` | 1 | completed runs kept per app; `0` keeps all runs. Also configurable with `STOVE_RETENTION_RUNS_PER_APP` |
| `--clear` | off | clear stored runs and exit |
| `--fresh-start` | off | back up and recreate the SQLite DB before serving; unsupported with PostgreSQL |

## Acceptance and load tests

From `tools/stove-cli`, run the public-boundary acceptance suite with a Docker-compatible daemon available:

```bash
cargo test --test acceptance
cargo test --test load -- --nocapture
```

The acceptance suite verifies the same ingestion, filtering, retention, administration, UI, and MCP behavior against SQLite and a disposable PostgreSQL instance created with `testcontainers` 0.28.0. The separate load test seeds 50,000 PostgreSQL runs, requires the planner to use the metadata GIN index, and mixes concurrent dashboard, REST, admin, and MCP reads under a configurable p95 latency budget. See `tools/stove-cli/acceptance-tests/README.md` for tuning variables and the manual browser workflow.

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
| Disk filling up | Set `--retention-runs-per-app` (or `STOVE_RETENTION_RUNS_PER_APP`) to limit completed runs per app |
