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

=== "Docker"

    Pull a versioned multi-platform image from GHCR:

    ```bash
    docker pull ghcr.io/trendyol/stove-cli:0.26.0
    ```

    Replace `0.26.0` with the Stove version used by your tests.

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
stove --config-file /etc/stove/stove.toml
stove --clear                          # clear stored runs and exit
```

Open the printed URL. Empty until tests run.

### Configuration files and secrets

Production deployments can mount a TOML or JSON configuration file and keep the PostgreSQL URL in a separate secret file. For example, `/etc/stove/stove.toml` can contain:

```toml
port = 4040
grpc_port = 4041
database_url_file = "/run/secrets/stove/database-url"
retention_runs_per_app = 50
```

The equivalent JSON keys are identical:

```json
{
  "port": 4040,
  "grpc_port": 4041,
  "database_url_file": "/run/secrets/stove/database-url",
  "retention_runs_per_app": 50
}
```

Start it with `stove --config-file /etc/stove/stove.toml`, or set `STOVE_CONFIG_FILE` to the path. A secret file contains only the connection URL and may end with a newline. `--database-url-file` and `STOVE_DATABASE_URL_FILE` provide the same indirection without a general configuration file.

CLI arguments and `STOVE_*` environment variables override the configuration file, which overrides built-in defaults. Configure only one of `database_url` and `database_url_file` at a given precedence level. Relative `db` and `database_url_file` paths inside a configuration file are resolved from that file's directory. Stove reads configuration and secrets once during startup, so restart replicas after rotating either file. Prefer a read-only secret mount over an inline URL, an environment variable, or a command-line argument because those values can be exposed by deployment inspection or process listings.

## Run with Docker

The image serves the dashboard, REST API, and MCP on port `4040`, and receives test events over gRPC on port `4041`. It runs as a non-root user and supports Linux AMD64 and ARM64.

For a local or single-host installation, persist SQLite at `/data`:

```bash
docker run -d \
  --name stove \
  --restart unless-stopped \
  -p 4040:4040 \
  -p 4041:4041 \
  -v stove-data:/data \
  ghcr.io/trendyol/stove-cli:0.26.0
```

### Local PostgreSQL stack

To run the current source against PostgreSQL without preparing a database manually:

```bash
cd tools/stove-cli
just postgres-up
```

This builds the local Stove image, starts PostgreSQL 18, waits for it to become healthy, applies Stove migrations, and exposes:

- dashboard, REST, admin, and MCP at `http://localhost:4040`;
- gRPC ingestion at `localhost:4041`;
- PostgreSQL at `localhost:5433`, using `stove` for the local-only database, user, and password.

The PostgreSQL volume survives normal restarts. Use `just postgres-logs` to follow Stove, `just postgres-down` to stop both containers, or `just postgres-reset` to stop them and permanently delete the local database volume. Override ports or retention with `STOVE_HTTP_PORT`, `STOVE_GRPC_PORT`, `STOVE_POSTGRES_PORT`, and `STOVE_RETENTION_RUNS_PER_APP` before running `just postgres-up`.

For a shared installation, mount a PostgreSQL URL secret and choose how many completed runs to retain per application:

```bash
docker run -d \
  --name stove \
  --restart unless-stopped \
  -p 4040:4040 \
  -p 4041:4041 \
  -v /secure/stove/database-url:/run/secrets/stove-database-url:ro \
  -e STOVE_DATABASE_URL_FILE=/run/secrets/stove-database-url \
  -e STOVE_RETENTION_RUNS_PER_APP=50 \
  ghcr.io/trendyol/stove-cli:0.26.0
```

The container applies database migrations when it starts. PostgreSQL uses TLS by default; append `?sslmode=disable` only when the database intentionally has no TLS and the connection stays inside a trusted private network. No `/data` volume is needed with PostgreSQL.

### Run multiple replicas

Multiple Stove pods must all use the same PostgreSQL database. SQLite is intentionally single-process and must not be placed on a shared volume. Put both HTTP and gRPC ports behind services or load balancers; session affinity is not required.

Each dashboard event carries a stable UUID and a per-run sequence. A pod commits the domain update, deduplication record, and durable live event in one PostgreSQL transaction before acknowledging it. PostgreSQL advisory locks serialize events for the same run, preserve commit order in the live-event log, and coordinate retention pruning for the same application. The UI receives cross-pod updates through PostgreSQL `LISTEN/NOTIFY`, with durable outbox polling and `Last-Event-ID` replay as the correctness fallback. PostgreSQL is therefore both the data store and the coordination dependency; no Redis or message broker is required.

Allow four PostgreSQL connections per replica (read, write, database explorer, and notification listener), plus operational headroom. Run the same Stove image version on every replica. Concurrent first starts are safe because migrations are serialized in PostgreSQL.

For Kubernetes, use `/api/v1/meta` as a startup/readiness endpoint, expose `4040` for UI/REST/MCP and `4041` for gRPC, and give SIGTERM at least 10 seconds to drain. Mount ordinary settings from a `ConfigMap` and the connection URL from a `Secret`:

```yaml
apiVersion: v1
kind: ConfigMap
metadata: { name: stove-config }
data:
  stove.toml: |
    port = 4040
    grpc_port = 4041
    database_url_file = "/run/secrets/stove/database-url"
    retention_runs_per_app = 50
---
apiVersion: apps/v1
kind: Deployment
metadata: { name: stove }
spec:
  replicas: 2
  selector: { matchLabels: { app: stove } }
  template:
    metadata: { labels: { app: stove } }
    spec:
      securityContext: { fsGroup: 10001 }
      terminationGracePeriodSeconds: 15
      containers:
        - name: stove
          image: ghcr.io/trendyol/stove-cli:0.26.0
          args: ["--config-file", "/etc/stove/stove.toml", "--no-skills-check"]
          ports:
            - { name: http, containerPort: 4040 }
            - { name: grpc, containerPort: 4041 }
          volumeMounts:
            - { name: config, mountPath: /etc/stove, readOnly: true }
            - { name: database-url, mountPath: /run/secrets/stove, readOnly: true }
          readinessProbe:
            httpGet: { path: /api/v1/meta, port: http }
      volumes:
        - name: config
          configMap: { name: stove-config }
        - name: database-url
          secret:
            secretName: stove-postgres
            defaultMode: 0440
            items:
              - { key: url, path: database-url }
```

On a new PostgreSQL database, the first replica seeds the shared retention value from its resolved startup configuration. Later replicas do not overwrite it. Changes made on `/admin` are stored in PostgreSQL, immediately visible to every replica, and survive restarts.

After deployment:

- Users open `http://stove.internal:4040` and administer retained evidence at `/admin`.
- Agents connect to the Streamable HTTP MCP endpoint at `http://stove.internal:4040/mcp`.
- Test processes set `ingestion = DashboardIngestion.Grpc(host = "stove.internal", port = 4041)` so events reach the exposed gRPC port.

The release workflow publishes exact (`0.26.0`), minor (`0.26`), major (`0`), and `latest` tags for stable releases. Pin the exact tag in production so a restart cannot pull a different server version. Keep that version aligned with the Stove dependencies used by the test process.

!!! warning "Trusted networks only"
    The container does not add authentication or authorization. Expose ports `4040` and `4041` only through an internal network, firewall, VPN, or private ingress. Do not publish either port directly to the internet.

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
        // DashboardSystemOptions(
        //     appName = "my-service",
        //     ingestion = DashboardIngestion.Grpc(port = 9001)
        // )
    }
    // ... other systems + runner
}.run()
```

Now the registered Stove systems stream test events to the dashboard while the CLI is running.

Metadata is an immutable set of string key/value pairs attached when a run starts. Keys are intentionally open-ended, so CI jobs can describe a run with team, project, pipeline, branch, environment, or any other useful dimensions without a server schema change.

### Ingestion over HTTP(S)

By default events are streamed to the CLI over plaintext gRPC at `localhost:4041`. Configure another gRPC endpoint with `DashboardIngestion.Grpc(host, port)`. When the CLI sits behind an HTTPS-only ingress or API gateway that cannot forward gRPC — for example a shared dashboard server for CI pipelines — select HTTP ingestion instead:

```kotlin
DashboardSystemOptions(
    appName = "checkout-api",
    ingestion = DashboardIngestion.Http("https://stove-gateway.internal/stove"),
    metadata = mapOf("gitlab.pipeline_id" to (System.getenv("CI_PIPELINE_ID") ?: "local"))
)
```

With `DashboardIngestion.Http`, each event is sent as a protobuf-encoded `DashboardEvent` in the body of `POST <baseUrl>/api/v1/events` (`Content-Type: application/x-protobuf`) and acknowledged with a protobuf `EventAck`. The base URL may include the gateway path prefix but must not include `/api/v1/events`, a query, or a fragment. Queueing, retry, and auto-disable semantics are identical to the default `DashboardIngestion.Grpc` transport, and the server applies the same validation, deduplication, and live broadcast pipeline to both.

The server exposes its HTTP API documentation at `/swagger-ui` and its OpenAPI document at `/api-docs/openapi.json`. The ingestion request and response are binary protobuf payloads; the shared `.proto` files in `stove-dashboard-api` remain the authoritative message schema.

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

By default, Stove retains the newest completed run for each application, preserving the previous local behavior. Set `--retention-runs-per-app N` or `STOVE_RETENTION_RUNS_PER_APP=N` to keep more; `0` keeps unlimited history. Running runs are not removed by automatic retention. In PostgreSQL mode the value is shared and persisted; in SQLite mode it belongs to the current process.

## Storage backends

SQLite remains the zero-configuration default and stores data at `~/.stove-dashboard.db`. For a server shared by CI jobs, teams, and agents, configure PostgreSQL with either form:

```bash
stove --database-url 'postgresql://stove:secret@db.example/stove'
STOVE_DATABASE_URL='postgresql://stove:secret@db.example/stove' stove
stove --database-url-file /run/secrets/stove/database-url
```

PostgreSQL connections use TLS by default. Add `?sslmode=disable` only for a trusted PostgreSQL endpoint that intentionally has no TLS. Stove applies versioned migrations at startup. [Refinery](https://github.com/rust-db/refinery) owns migration discovery and history (`refinery_schema_history`); backend-specific SQL lives in parallel `tools/stove-cli/src/storage/migrations/sqlite/` and `tools/stove-cli/src/storage/migrations/postgres/` directories. [Diesel](https://github.com/diesel-rs/diesel) owns normal reads and writes. Raw SQL is limited to backend-specific coordination and queries whose CTE, JSON attribution, aggregation, or SQLite `rowid` behavior is not usefully expressed by the ORM.

This storage rewrite is intentionally a clean break. Databases created by a CLI version that recorded migrations in `schema_migrations` are not upgraded or imported. Delete the local SQLite database, or recreate the PostgreSQL database/schema, before starting this version. Run metadata is stored as `JSONB` with a GIN `jsonb_path_ops` index, so dynamic exact-subset filters remain efficient without predeclaring keys.

`--db` and `--fresh-start` are SQLite-only; Stove rejects `--fresh-start` when PostgreSQL is selected. `--clear` operates on the selected backend.

## Fault tolerance

Dashboard is **opt-in** and **non-blocking**:

- Events queue locally; publishing (gRPC or HTTP) happens in the background.
- If the CLI is down or unreachable, the emitter auto-disables for the rest of the suite. Tests continue. No flakes.
- Tests never wait on the dashboard.

## REST API

The CLI exposes REST endpoints for integration:

| Endpoint | Use |
|---|---|
| `GET /api/v1/meta` | discovery; version, capabilities, MCP availability |
| `GET /api/v1/apps` | list registered apps |
| `POST /api/v1/events` | ingest a protobuf-encoded `DashboardEvent`; responds with a protobuf `EventAck` |
| `GET /api/v1/runs?app=...&metadata=...` | list runs by app and/or an exact metadata subset |
| `GET /api/v1/runs/{run}/tests` | tests in a run |
| `GET /api/v1/runs/{run}/mock-interactions` | mock exchanges for a run; append `/ambient` for unattributed exchanges |
| `GET /api/v1/runs/{run}/mock-warnings` | mock diagnostics for a run; append `/ambient` for unattributed diagnostics |
| `GET /api/v1/runs/{run}/tests/{test}/mock-interactions` | mock exchanges attributed to a test |
| `GET /api/v1/runs/{run}/tests/{test}/mock-warnings` | mock diagnostics attributed to a test |
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

Select **Admin** in the dashboard header to open the dedicated `/admin` page, where you can inspect storage, browse the active database, run SQL, change runtime retention, preview and purge matching runs, or clear all data. SQL mutations and destructive administration operations require confirmation. A retention change prunes excess completed runs immediately. It lasts for the current process with SQLite and is persisted as a shared setting with PostgreSQL.

Purge preview accepts an optional application and RFC 3339 `older_than` cutoff, then returns the exact run IDs and evidence counts. Purging uses those IDs. Both preview and purge exclude active runs unless `include_running` is explicitly `true`.

| Endpoint | Use |
|---|---|
| `GET /api/v1/admin/status` | backend, retention, run, and evidence counts |
| `GET /api/v1/admin/database/schema` | active backend, tables, columns, nullability, and primary keys |
| `POST /api/v1/admin/database/query` | run one SQL statement with `sql` and optional `max_rows` |
| `PUT /api/v1/admin/retention` | set runtime retention with `{"runs_per_app": 50}` |
| `POST /api/v1/admin/purge/preview` | preview with `app_name`, `older_than`, and `include_running` |
| `POST /api/v1/admin/purge` | delete exact `run_ids`; active runs still require `include_running` |
| `DELETE /api/v1/data` | clear every run and all evidence |

### Database explorer

The database explorer is implemented by Stove itself and runs inside the Rust process; it does not require a sidecar or separate admin service. The UI provides table and column discovery, editable SELECT/INSERT/UPDATE/DELETE templates, a result grid, and confirmation before running a statement it considers mutating. The same operations are available through REST:

```bash
curl http://localhost:4040/api/v1/admin/database/schema

curl -X POST http://localhost:4040/api/v1/admin/database/query \
  -H 'Content-Type: application/json' \
  -d '{"sql":"SELECT id, app_name FROM runs ORDER BY started_at DESC","max_rows":100}'
```

The query endpoint accepts one statement up to 64 KiB. `max_rows` defaults to 100 and is clamped to 1–500. PostgreSQL SELECT results use a server-side cursor and fetch only `max_rows + 1` records, so the `truncated` flag does not require materializing an unbounded result in Stove. PostgreSQL statements have a 10-second timeout. SQLite uses a dedicated peer connection and stops stepping through the result after the truncation check. Non-row statements return `affected_rows`; row values are returned as strings or `null`.

The explorer has direct write access to Stove's tables. Confirmation in the browser is a safety prompt, not an authorization boundary, and direct SQL changes can violate application invariants. Keep the endpoint on a trusted operator network and prefer the purpose-built retention, purge, and clear controls for routine operations.

!!! warning "Trusted networks only"
    The dashboard, REST API, database explorer, admin operations, gRPC ingestion, and MCP endpoint intentionally have no authentication or authorization. The servers listen on all interfaces so remote CI jobs and agents can connect. Deploy Stove only on an internal trusted network and control exposure outside Stove (for example with firewall rules or a private ingress).

## CLI options reference

| Flag | Default | Notes |
|---|---|---|
| `--config-file` | unset | TOML or JSON configuration path; also `STOVE_CONFIG_FILE` |
| `--port` | 4040 | web UI, REST, and MCP; also `STOVE_PORT` |
| `--grpc-port` | 4041 | event ingestion from Stove tests; also `STOVE_GRPC_PORT` |
| `--db` | `~/.stove-dashboard.db` | persistence path; also `STOVE_DB` |
| `--database-url` | unset | PostgreSQL URL; replaces SQLite. Also configurable with `STOVE_DATABASE_URL` |
| `--database-url-file` | unset | file containing the PostgreSQL URL; also `STOVE_DATABASE_URL_FILE` |
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
| Events not arriving | Port mismatch. `DashboardIngestion.Grpc(port = ...)` must match `--grpc-port` |
| "gRPC disabled" warning | Expected if CLI started after tests; restart in correct order |
| Disk filling up | Set `--retention-runs-per-app` (or `STOVE_RETENTION_RUNS_PER_APP`) to limit completed runs per app |
