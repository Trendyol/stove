# Stove Dashboard — Metadata, Storage, and Administration

Use this guide when configuring run metadata, operating a shared Stove server, choosing SQLite or PostgreSQL, setting retention, filtering runs, or administering stored evidence. For agent tool calls after selecting a run, continue with [mcp.md](mcp.md).

For continuous profiling and **Drill Down**, follow [profiling.md](profiling.md).
Its upload URL is configured independently of dashboard event ingestion.

## Publish run metadata

Metadata is attached when the dashboard run starts and applies to every test and evidence record in that run:

```kotlin
dashboard {
    DashboardSystemOptions(
        appName = "checkout-api",
        metadata = mapOf(
            "team" to "checkout",
            "tribe" to "commerce",
            "gitlab.project" to (System.getenv("CI_PROJECT_PATH") ?: "local"),
            "gitlab.pipeline_id" to (System.getenv("CI_PIPELINE_ID") ?: "local"),
            "gitlab.job_id" to (System.getenv("CI_JOB_ID") ?: "local")
        )
    )
}
```

Keep these invariants:

- Keys are dynamic; the server does not require a schema.
- Keys and values must be strings. Convert numeric pipeline or job IDs to strings.
- Prefer stable, namespaced keys such as `gitlab.project`, `gitlab.pipeline_id`, `team`, and `environment`.
- Filters accept a string or non-empty list of strings per key: exact equality, OR within a key, AND across keys. Published run metadata remains one string per key.
- A pipeline can contain several jobs, shards, or retries. Include job/shard/attempt identifiers when available, then select the returned `run_id`; team and pipeline alone may be ambiguous.
- Metadata is a selector, not a security boundary. Stove has no authentication or authorization.

`DashboardIngestion.Grpc(host, port)` identifies the Stove gRPC ingestion endpoint. Point it at the shared server and its gRPC port when the tests and server run on different hosts.

### Shared server behind an HTTP(S) gateway

For an HTTPS-only ingress, use HTTP event ingestion with the externally reachable base URL:

```kotlin
dashboard {
    DashboardSystemOptions(
        appName = "checkout-api",
        ingestion = DashboardIngestion.Http("https://stove.internal/observe")
    )
}
```

Set `public_url = "https://stove.internal/observe"` in the server configuration, or use
`--public-url` / `STOVE_PUBLIC_URL`, so MCP citations and browser links use that address. The server serves root paths
and prefixed paths; the gateway can preserve or strip `/observe`. Public URLs accept HTTP(S) without credentials,
query, or fragment. Event ingestion posts to `<baseUrl>/api/v1/events`; its base URL must not already include that
endpoint, a query, or a fragment. Verify ingestion with a real test and open its returned citation through the gateway.
Configure profiling uploads separately as described in [profiling.md](profiling.md).

## Choose a storage backend

SQLite is the zero-configuration local default:

```bash
stove
stove --db ./my-stove.sqlite
```

It stores data at `~/.stove-dashboard.db` unless `--db` is set. For a shared server with concurrent CI jobs and agents, use PostgreSQL:

```bash
stove --database-url 'postgresql://stove:secret@db.example/stove'
STOVE_DATABASE_URL='postgresql://stove:secret@db.example/stove' stove
stove --database-url-file /run/secrets/stove/database-url
```

For production, prefer `stove --config-file /etc/stove/stove.toml` (or `STOVE_CONFIG_FILE`) with ordinary settings in TOML or JSON and `database_url_file = "/run/secrets/stove/database-url"` pointing to a separately mounted secret. The same key is available as `--database-url-file` or `STOVE_DATABASE_URL_FILE`. CLI/environment values override the file, which overrides defaults. Relative paths declared in the config resolve from its directory; files are read once at startup.

PostgreSQL uses TLS by default. Add `sslmode=disable` only for an intentionally non-TLS endpoint on a trusted network. Stove applies versioned migrations at startup:

- SQLite: `server/stove-server/src/storage/migrations/sqlite/`
- PostgreSQL: `server/stove-server/src/storage/migrations/postgres/`

Refinery discovers those migrations and records them in `refinery_schema_history`. Diesel handles ordinary persistence; raw SQL is reserved for database-specific coordination and complex queries. Databases with the former `schema_migrations` history are not supported for in-place upgrade. Back up retained evidence and point the new server at a fresh SQLite file or PostgreSQL database; consult the version's migration guidance before replacing an existing database.

PostgreSQL stores metadata as `JSONB` and indexes it with GIN `jsonb_path_ops`. SQLite provides the same exact-subset behavior through application filtering. `--fresh-start` is SQLite-only; `--clear` operates on whichever backend is selected.

### Multiple server replicas

Use only PostgreSQL when running more than one Stove server replica, and configure every replica with the same database URL and Stove image version. Put both the HTTP and gRPC ports behind services; neither requires session affinity. Do not share a SQLite file between pods.

PostgreSQL coordinates per-run event ordering, live-event commit order, and per-application retention with advisory locks. Each ACK follows one transaction containing the domain write, event inbox record, and durable live event. UUID-based retry deduplication prevents a retried event from being applied twice. Cross-pod SSE uses `LISTEN/NOTIFY` for wake-ups and durable polling plus `Last-Event-ID` replay for correctness. No Redis or broker is needed.

Budget up to eight PostgreSQL connections per replica (four readers, one writer, two database explorer connections, and a dedicated live-event listener) plus headroom. The first replica on a new database seeds the shared retention setting; Admin-page changes persist in PostgreSQL and all replicas observe them. Use `/api/v1/meta` for startup/readiness and allow at least 10 seconds after SIGTERM for graceful drain.

## Run the packaged server

The current release workflow publishes `ghcr.io/trendyol/stove-server` for Linux AMD64; ARM64 publishing is disabled. Check a particular tag with `docker buildx imagetools inspect ghcr.io/trendyol/stove-server:$STOVE_VERSION` before selecting another architecture. Set `STOVE_VERSION` to the exact released version resolved by the tests, from the project's version catalog or Gradle build, before running the commands below.

For SQLite, mount `/data`; the image defaults to `/data/stove.db`:

```bash
docker run -d --name stove --restart unless-stopped \
  -p 4040:4040 -p 4041:4041 \
  -v stove-data:/data \
  "ghcr.io/trendyol/stove-server:${STOVE_VERSION:?Set STOVE_VERSION to the project Stove version}"
```

For a current-source local server backed by PostgreSQL, run `just postgres-up` from `server/stove-server`. It builds the image and exposes HTTP/MCP on `4040`, gRPC on `4041`, and PostgreSQL on `5433`. Use `just postgres-down` to preserve data or `just postgres-reset` to delete the local PostgreSQL volume.

For PostgreSQL, mount the connection URL as a read-only secret and omit the SQLite volume:

```bash
docker run -d --name stove --restart unless-stopped \
  -p 4040:4040 -p 4041:4041 \
  -v /secure/stove/database-url:/run/secrets/stove-database-url:ro \
  -e STOVE_DATABASE_URL_FILE=/run/secrets/stove-database-url \
  -e STOVE_RETENTION_PASSED_RUNS_PER_APP=50 \
  -e STOVE_RETENTION_FAILED_RUNS_PER_APP=50 \
  "ghcr.io/trendyol/stove-server:${STOVE_VERSION:?Set STOVE_VERSION to the project Stove version}"
```

Port `4040` serves the UI, REST, admin page, and Streamable HTTP MCP endpoint (`/mcp`). Port `4041` is the gRPC ingestion endpoint configured through `DashboardSystemOptions(ingestion = DashboardIngestion.Grpc(host, port))`. Both ports must remain on a trusted internal network because Stove intentionally provides no authentication or authorization.

## Configure retention

Stove keeps the newest **3 passed and 5 failed runs per application**, counted independently. A passed run cannot evict
a failure. Set a quota to `0` for unlimited history of that outcome. Completed-run retention excludes active runs.

```bash
stove --retention-passed-runs-per-app 50 --retention-failed-runs-per-app 50
STOVE_RETENTION_PASSED_RUNS_PER_APP=50 STOVE_RETENTION_FAILED_RUNS_PER_APP=50 stove
stove --retention-passed-runs-per-app 0 --retention-failed-runs-per-app 0
```

Ordered metadata rules override the defaults: the first matching rule wins, with separate quotas per application,
outcome, and rule. Values listed together share a quota. Omitted limits inherit defaults; omitted `app_name` matches
all applications. Each rule requires non-empty metadata values and at least one outcome override:

```toml
retention_passed_runs_per_app = 3
retention_failed_runs_per_app = 5

[[retention_rules]]
app_name = "checkout-api"
failed_runs_per_app = 12
[retention_rules.metadata]
team = ["checkout", "payments"]
branch = ["main"]
```

CLI `--retention-rules` and `STOVE_RETENTION_RULES` accept a JSON array of these rules. Startup configuration seeds
a new database. Admin changes replace the complete policy, persist in both SQLite and PostgreSQL, take precedence
on subsequent starts, and immediately prune excess completed runs and evidence. PostgreSQL replicas share the policy.
Only retained runs are visible to the UI, REST, and MCP. The former single-limit CLI, environment, and API fields are removed.

Running runs have an independent idle expiry: **3600 seconds without newly committed ingestion**, checked at startup
and every 60 seconds. It can delete long, silent tests as well as abandoned jobs. `--run-idle-timeout-seconds`,
`STOVE_RUN_IDLE_TIMEOUT_SECONDS`, or config-file `run_idle_timeout_seconds` seeds a new database; `0` disables expiry.
The Admin idle timeout persists and overrides later startup settings. Deletion removes the run and its evidence;
duplicate retries do not refresh activity, and late events cannot revive an expired run.

## Find runs

The dashboard run picker builds selectable metadata keys and values from the application's retained runs. Users can combine selections instead of typing arbitrary values.

REST accepts a URL-encoded JSON object. For a survey covering checkout or payments in one pipeline:

```bash
curl --get 'http://stove.internal:4040/api/v1/runs' \
  --data-urlencode 'app=checkout-api' \
  --data-urlencode 'metadata={"team":["checkout","payments"],"gitlab.pipeline_id":"12345"}'
```

MCP applies the same semantics through `stove_runs`:

```json
{
  "app_name": "checkout-api",
  "status": "FAILED",
  "metadata": {
    "team": "checkout",
    "gitlab.pipeline_id": "12345"
  }
}
```

Select the exact returned `run_id` and use `stove_diagnose` for initial triage, then follow its evidence references. On older servers without that tool, use `stove_failures`; it does not accept metadata directly. See [mcp.md](mcp.md) for discovery, pagination, and run selection.

Mock evidence uses explicit REST resource names. Query `/api/v1/runs/{run_id}/mock-interactions` and `/api/v1/runs/{run_id}/mock-warnings`; append `/ambient` for unattributed evidence, or place the resource below `/tests/{test_id}` for evidence attributed to one test.

## Administer evidence

Open the dedicated `/admin` page to inspect storage, browse tables, run SQL, change runtime retention, preview a purge, purge selected runs, or clear all evidence. The UI requires confirmation for SQL mutations and final destructive actions. Purge excludes running runs unless `include_running` is explicitly true.

| Endpoint | Purpose |
|---|---|
| `GET /api/v1/admin/status` | Backend, retention, run, and evidence counts |
| `GET /api/v1/admin/database/schema` | Active backend, tables, columns, nullability, and primary keys |
| `POST /api/v1/admin/database/query` | Execute one statement with `{"sql":"SELECT ...","max_rows":100}` |
| `PUT /api/v1/admin/retention` | Replace policy with `{"passed_runs_per_app": 3, "failed_runs_per_app": 5, "rules": []}` |
| `PUT /api/v1/admin/idle-timeout` | Persist idle expiry with `{"run_idle_timeout_seconds": 3600}`; `0` disables it |
| `POST /api/v1/admin/purge/preview` | Preview by optional `app_name`, RFC 3339 `older_than`, and `include_running` |
| `POST /api/v1/admin/purge` | Delete exact `run_ids`, optionally including active runs |
| `DELETE /api/v1/data` | Clear all runs and evidence |

The database explorer is native to the Stove Rust process; it needs no sidecar. It discovers the SQLite or PostgreSQL schema and provides SELECT/INSERT/UPDATE/DELETE templates. Queries are limited to one 64-KiB statement and 1–500 returned rows (default 100). PostgreSQL applies a 10-second statement timeout and uses a server-side cursor for SELECT results, so truncation does not materialize the full result in Stove. Row values are strings or `null`; non-row statements report `affected_rows`.

MCP is intentionally read-only. Use only the Admin UI or REST endpoints for mutations. The database explorer can bypass application invariants, and its browser confirmation is not an authorization boundary.

## Deployment boundary

Stove intentionally has no authentication or authorization. The HTTP server, MCP, gRPC ingestion, database explorer, and admin operations accept remote clients. Deploy only on a trusted internal network, and enforce exposure with a firewall, private ingress, or equivalent boundary outside Stove.

## Verify changes

For downstream dashboard setup, run a small test suite and verify that its app, metadata, tests, and final status appear on the intended server. Check the configured ingestion transport and HTTP/MCP address from the hosts that use them; with a gateway, verify the public URL and prefix too.

For changes to Stove server storage, APIs, or ingestion itself, run the relevant server checks. From `server/stove-server`, with a Docker-compatible daemon running:

```bash
cargo test --test acceptance
cargo test --test load -- --nocapture
```

The acceptance suite runs against SQLite and a disposable PostgreSQL instance. Use the load suite for changes affecting storage/query performance: it seeds 50,000 PostgreSQL runs, verifies use of the metadata GIN index, and exercises concurrent dashboard, REST, admin, and MCP reads under a p95 latency budget. Tune it with `STOVE_LOAD_TEST_RUNS`, `STOVE_LOAD_TEST_REQUESTS`, `STOVE_LOAD_TEST_CONCURRENCY`, and `STOVE_LOAD_TEST_P95_MS`.

## References

- Dashboard component: `docs/Components/18-dashboard.md`
- MCP component: `docs/Components/21-mcp.md`
- server acceptance tests: `server/stove-server/acceptance-tests/README.md`
