# Stove Dashboard — Metadata, Storage, and Administration

Use this guide when configuring run metadata, operating a shared Stove server, choosing SQLite or PostgreSQL, setting retention, filtering runs, or administering stored evidence. For agent tool calls after selecting a run, continue with [mcp.md](mcp.md).

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
            "gitlab.pipeline_id" to (System.getenv("CI_PIPELINE_ID") ?: "local")
        )
    )
}
```

Keep these invariants:

- Keys are dynamic; the server does not require a schema.
- Keys and values must be strings. Convert numeric pipeline or job IDs to strings.
- Prefer stable, namespaced keys such as `gitlab.project`, `gitlab.pipeline_id`, `team`, and `environment`.
- Filtering uses exact string equality and AND-combines every supplied pair.
- Metadata is a selector, not a security boundary. Stove has no authentication or authorization.

`cliHost` and `cliPort` identify the Stove gRPC ingestion endpoint. Point them at the shared server and its gRPC port when the tests and CLI run on different hosts.

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
```

PostgreSQL uses TLS by default. Add `sslmode=disable` only for an intentionally non-TLS endpoint on a trusted network. Stove applies versioned migrations at startup:

- SQLite: `tools/stove-cli/src/storage/migrations/sqlite/`
- PostgreSQL: `tools/stove-cli/src/storage/migrations/postgres/`

PostgreSQL stores metadata as `JSONB` and indexes it with GIN `jsonb_path_ops`. SQLite provides the same exact-subset behavior through application filtering. `--fresh-start` is SQLite-only; `--clear` operates on whichever backend is selected.

## Configure retention

Stove retains the newest completed run per application by default, preserving its original local behavior. Running runs are never pruned by automatic retention.

```bash
stove --retention-runs-per-app 50
STOVE_RETENTION_RUNS_PER_APP=50 stove
stove --retention-runs-per-app 0  # unlimited
```

Only retained runs are visible to the UI, REST, and MCP. A runtime change on the Admin page prunes excess completed runs immediately but does not persist after process restart; use the CLI flag or environment variable for a durable startup setting.

## Find runs

The dashboard run picker builds selectable metadata keys and values from the application's retained runs. Users can combine selections instead of typing arbitrary values.

REST accepts a URL-encoded JSON object:

```bash
curl --get 'http://stove.internal:4040/api/v1/runs' \
  --data-urlencode 'app=checkout-api' \
  --data-urlencode 'metadata={"team":"checkout","gitlab.pipeline_id":"12345"}'
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

Use the returned `run_id` with `stove_failures` and the evidence tools. `stove_failures` does not accept metadata directly.

## Administer evidence

Open the dedicated `/admin` page to inspect storage, change runtime retention, preview a purge, purge selected runs, or clear all evidence. The UI requires confirmation for final destructive actions. Purge excludes running runs unless `include_running` is explicitly true.

| Endpoint | Purpose |
|---|---|
| `GET /api/v1/admin/status` | Backend, retention, run, and evidence counts |
| `PUT /api/v1/admin/retention` | Set runtime retention with `{"runs_per_app": 50}` |
| `POST /api/v1/admin/purge/preview` | Preview by optional `app_name`, RFC 3339 `older_than`, and `include_running` |
| `POST /api/v1/admin/purge` | Delete exact `run_ids`, optionally including active runs |
| `DELETE /api/v1/data` | Clear all runs and evidence |

MCP is intentionally read-only. Use only the Admin UI or REST endpoints for mutations.

## Deployment boundary

Stove intentionally has no authentication or authorization. The HTTP server, MCP, gRPC ingestion, and admin operations accept remote clients. Deploy only on a trusted internal network, and enforce exposure with a firewall, private ingress, or equivalent boundary outside Stove.

## Verify changes

From `tools/stove-cli`, with a Docker-compatible daemon running:

```bash
cargo test --test acceptance
cargo test --test load -- --nocapture
```

The acceptance suite runs against SQLite and a disposable PostgreSQL instance through `testcontainers` 0.28.0. The load test seeds 50,000 PostgreSQL runs, verifies use of the metadata GIN index, and exercises concurrent dashboard, REST, admin, and MCP reads under a p95 latency budget. Tune it with `STOVE_LOAD_TEST_RUNS`, `STOVE_LOAD_TEST_REQUESTS`, `STOVE_LOAD_TEST_CONCURRENCY`, and `STOVE_LOAD_TEST_P95_MS`.

## References

- Dashboard component: `docs/Components/18-dashboard.md`
- MCP component: `docs/Components/21-mcp.md`
- CLI acceptance tests: `tools/stove-cli/acceptance-tests/README.md`
