# Monitoring a deployed Stove server

Stove uses the Rust `prometheus` crate for its registry, counters, histograms and text encoder. It exposes Prometheus text metrics at **`GET /metrics`** on its existing HTTP port (4040 by default). Collection starts automatically for SQLite and PostgreSQL. No additional service or admin UI is required. Configure Prometheus with [prometheus.yml](prometheus.yml), adjust the target addresses, and load [alerts.yml](alerts.yml). The thresholds are starting points; tune them against your workload.

With `public_url = "https://stove.example/observe"`, both `/observe/metrics` and `/metrics` work, matching the server's existing prefix handling. Use your deployment's usual ingress authentication/network access configuration for the endpoint.

```sh
curl -fsS http://localhost:4040/metrics
```

**Scrape each server/pod directly**, using static targets or your Prometheus service discovery. A load-balanced target alternates unrelated process counters. `stove_instance` identifies the configured `STOVE_INSTANCE_ID` (or `HOSTNAME`, falling back to `stove`), PID, and a unique process generation. `backend` is `sqlite` or `postgresql`. Prometheus additionally supplies its usual `job` and `instance` labels. A process restart resets its counters; Prometheus retains history.

Operations and acquisition waits update counters/histograms as they happen. Capacity is sampled every **10 seconds**; role probes and diagnostics run every **30 seconds**. Database scraping reads cached values, including when the database is unavailable. On Linux, the application process collector also reads local `/proc` statistics; no scrape executes SQL. Failed diagnostics retain their previous values and expose `diagnostics_up=0`; always pair diagnostic values with their age. There is one diagnostic worker per repository, and a stalled probe cannot spawn an accumulating queue of SQL workers.

## Application metrics

Application metrics share the database registry and its `stove_instance` / `backend` labels. The `prometheus` crate supplies instruments, timers, encoding and Linux process collection. Names below have the `stove_app_` prefix.

| Metric | Meaning |
| --- | --- |
| `http_requests_total` | Responses by route template, bounded method and status, including `cancelled`. Unknown routes share `unmatched`; request data never becomes a label. Scrapes are excluded. |
| `http_request_duration_seconds`, `http_requests_in_flight` | Handler latency and active handlers through response headers, including body extraction but excluding streaming body lifetime. |
| `process_*` | Built-in **Linux-only** process metrics, including CPU seconds, resident/virtual memory, file descriptors and threads. CPU rate measures cores used; container limits and throttling require infrastructure metrics. |
| `ingestion_in_flight`, `ingestion_oldest_pending_age_seconds` | Unfinished decoded events and oldest attempt age, including database waits. Age is zero when idle. Ingestion commits inline; there is no separate app queue. Producer/network buffers are excluded. |
| `ingestion_duration_seconds`, `ingestion_events_total` | Preparation-to-commit latency and attempts by `accepted`, `duplicate` or `error`. Errors include validation, persistence failures and panics. Malformed HTTP protobuf appears as HTTP 4xx before ingestion. |
| `streams_active`, `stream_errors_total` | SSE/gRPC streams and receive/cursor/replay errors by transport. Ingestion failures are counted in ingestion outcomes. |
| `sse_lagged_events_total` | Broadcast events missed by slow SSE receivers, triggering durable replay; does not imply permanent loss. |
| `worker_runs_total`, `worker_last_success_timestamp_seconds` | Success/error attempts and last success or listener heartbeat. Covers capacity/diagnostics, relay, startup cursor, PostgreSQL notifications and runtime heartbeat. Errors include panics/cancellation. Zero timestamp means no success yet; growing age reveals stalled work. Startup cursor is excluded from freshness panels. |
| `runtime_scheduling_delay_seconds` | Delay beyond a one-second Tokio heartbeat. Pair with worker freshness: a complete stall produces no new observations. |

HTTP and gRPC share the existing synchronous ingestion path. Drop guards release active counts on cancellation or unwinding. PostgreSQL notification failures fall back to relay polling.

Compare request and ingestion latency with database acquisition time, CPU and runtime delay to locate pressure. High acquisition time suggests pool/lock contention; high CPU or runtime delay suggests app execution pressure. These signals help diagnosis, but cannot automatically identify every root cause. Unreachable processes require your infrastructure's scrape-target alerts.

## Database metrics

All names below have the `stove_db_` prefix. Durations are in seconds; capacities and counters are counts; storage sizes are bytes. Histograms include standard `_bucket`, `_count`, and `_sum` series.

| Metric | Meaning |
| --- | --- |
| `duration_seconds` | Histogram labeled by `kind`, `operation`, and `role`. `kind="operation"` includes acquisition waits; `execution` subtracts instrumented pool/mutex/advisory-lock acquisition time, but still includes SQL execution, other DB lock waits and transaction commit. `wait` measures each acquisition; `maintenance` measures the combined automatic retention work per enclosing transaction. |
| `errors_total`, `timeouts_total`, `busy_errors_total` | Failures by the same labels. Timeout counts cover pool timeouts and identifiable database statement/lock timeout errors. Busy counts cover SQLite busy/locked errors. Counts overlap between kinds: use `kind="operation"` for overall operation failures. Invalid explorer SQL or invalid persisted event ordering can also fail operations. |
| `last_success_timestamp_seconds` | Last success by kind, operation and role; retention successes are published only after the enclosing transaction commits. Absent until first success. |
| `capacity_maximum`, `capacity_connections`, `capacity_idle`, `capacity_busy`, `capacity_waiters` | Per-role application capacity. PostgreSQL uses separate pools: write=1, read=4, explorer=2. SQLite uses one mutex-protected connection per role. Waiters are current instrumented acquisition attempts, including advisory locks for the write role. These are not total PostgreSQL server connections. |
| `events_committed_total`, `events_duplicate_total` | Newly committed ingestion events and accepted duplicate retries, counted separately after successful transaction completion. Use these for ingestion throughput. |
| `retention_runs_deleted_total`, `purge_runs_deleted_total` | Runs removed by committed automatic retention or explicit purge. Clear-all and arbitrary explorer SQL are not included. Purge latency/failures use operation `purge_runs`; retention uses kind `maintenance`, operation `retention`. |
| `health`, `health_duration_seconds`, `health_age_seconds` | Latest availability probe by role. `health=1` means a small read through that connection role succeeded. A busy SQLite mutex or exhausted PostgreSQL pool can produce 0. A write-role probe does not perform a synthetic write; actual commit errors provide write-success evidence. |
| `sample_age_seconds`, `diagnostics_age_seconds`, `diagnostics_up` | Collector freshness and whether the last diagnostic attempt succeeded. Diagnostics age is absent before the first success; `diagnostics_up=0` covers initial unavailability. |
| `process_start_timestamp_seconds` | Process-local registry creation time. |
| `database_bytes` | SQLite allocated database pages (excluding WAL), or PostgreSQL's database-wide size, including indexes. |
| `wal_bytes`, `disk_available_bytes`, `free_page_bytes` | SQLite WAL file size, filesystem available space, and reusable database pages. In-memory SQLite omits file/disk metrics. Free pages do not mean the DB file has shrunk. |
| `checkpoint_probe_up`, `checkpoint_probe_busy`, `wal_frames`, `checkpointed_frames` | SQLite checkpoint-counter probe outcome and progress. The collector uses **`PRAGMA wal_checkpoint(NOOP)`** (supported by the bundled SQLite) every 30 seconds for disk-backed WAL databases. This reads counters without checkpointing or truncating pages; the SQLite busy timeout is 100 ms. WAL file size may stay high after successful checkpoints because SQLite reuses it. Fewer checkpointed frames than WAL frames can indicate readers holding snapshots. |
| `blocked_sessions`, `oldest_transaction_seconds` | PostgreSQL database-wide lock-waiting sessions and oldest transaction age, excluding the collector itself. |
| `session_diagnostics_available` | Whether session diagnostics were available in the cached diagnostic result. Insufficient permissions omit session values instead of reporting zero. |
| `postgres_deadlocks_total`, `postgres_stats_reset_timestamp_seconds` | Database-wide PostgreSQL deadlocks and statistics reset time. These counters belong to PostgreSQL and can reset independently of Stove. |
| `dead_tuples`, `last_autovacuum_timestamp_seconds` | Estimated dead tuples across user tables in the current schema, and the most recent autovacuum across those tables. The timestamp alone does not establish that every table is being vacuumed. |

Operation labels are fixed repository method names, for example `get_runs_filtered`, `commit_dashboard_event`, `purge_runs`, and `execute_database_query`. Wait operations are `checkout` (PostgreSQL), `mutex` (SQLite), `advisory_run`, and `advisory_live_event` (PostgreSQL). Labels never include SQL, URLs, credentials, run IDs, application names, or trace IDs.

PostgreSQL diagnostics use one additional short-lived read-only connection per collection, separate from the application pools and the existing LISTEN connection. The connection timeout is 2 seconds, with server-side statement timeout of 1 second and lock timeout of 250 ms. Existing URL options, including `search_path`, are preserved. Role probes acquire each application pool with a 250 ms timeout and use transaction-local SQL timeouts. Network failures can outlast server-side timeouts; monitor collection freshness.

Session statistics need `pg_read_all_stats` or equivalent permission. If desired, have your database administrator grant this to the Stove database role:

```sql
GRANT pg_read_all_stats TO stove;
```

Stove does not grant itself permissions. PostgreSQL filesystem capacity comes from your database host/cloud monitoring. Database-wide values are repeated by each Stove process connected to the same database: **do not sum them across replicas**.

## Grafana / PromQL

The **Job** selector discovers scrape jobs from Stove metrics and defaults to **All**. The scrape job is assigned by Prometheus and may differ from the application name (for example, Kubernetes discovery may use `kubernetes-pods`). If every section is empty, query `stove_db_process_start_timestamp_seconds` in Explore using the same data source, then select its `job` label. Existing dashboard URLs can retain an old `var-job` value after reimporting; clear that parameter or select the correct job. Only sections for deployed backends have data.

Import [grafana-dashboard.json](grafana-dashboard.json) using **Grafana → Dashboards → New → Import → Upload dashboard JSON file**. After importing, select your Prometheus data source, job, and instance at the top. The **App** section shows HTTP traffic, CPU/memory, pending ingestion and latency, streams, worker health, runtime scheduling delay, and retention. Expand **SQLite** or **PostgreSQL** for backend-specific database health, growth, performance, and diagnostics. The database sections start collapsed so panels for an unused backend do not load. It uses built-in Grafana panels and requires no extra plugins.

The dashboard covers health, collector freshness, database growth, latency, contention, ingestion, retention, and backend-specific diagnostics. The **Growth window** selector controls trend calculations. SQLite's estimated days until disk full extrapolates the decline in filesystem free space, including other applications on the same filesystem; it shows no value when space is flat/increasing or diagnostics are stale/unavailable. This estimate is not a guarantee. PostgreSQL disk capacity still requires host/cloud metrics. Unsupported or unavailable diagnostics show **No data**, rather than zero. The JSON does not install alert rules; configure [alerts.yml](alerts.yml) separately in Prometheus.

Use these in your existing Grafana dashboards. Filter to the relevant job/instance if you monitor multiple deployments. Ingestion can legitimately be idle; no recent commit on its own is not a failure.

Read/write p95, including acquisition wait (change `0.95` to `0.50` or `0.99`):

```promql
histogram_quantile(0.95,
  sum by (le, instance, stove_instance, role) (
    rate(stove_db_duration_seconds_bucket{kind="operation",role=~"read|write"}[5m])
  )
)
```

Database operation execution p95, excluding instrumented acquisition waits:

```promql
histogram_quantile(0.95,
  sum by (le, instance, stove_instance, role) (
    rate(stove_db_duration_seconds_bucket{kind="execution"}[5m])
  )
)
```

Acquisition p95, distinguishing connection checkout from advisory locks:

```promql
histogram_quantile(0.95,
  sum by (le, instance, stove_instance, role, operation) (
    rate(stove_db_duration_seconds_bucket{kind="wait"}[5m])
  )
)
```

Committed events/second and ingestion commit failures:

```promql
rate(stove_db_events_committed_total[5m])
rate(stove_db_errors_total{kind="operation",operation="commit_dashboard_event"}[5m])
```

Pool/connection occupancy and database size growth (bytes/second):

```promql
stove_db_capacity_busy / stove_db_capacity_maximum
deriv(stove_db_database_bytes[1h])
```

Retention duration p95 and runs deleted:

```promql
histogram_quantile(0.95,
  sum by (le, instance, stove_instance) (
    rate(stove_db_duration_seconds_bucket{kind="maintenance",operation="retention"}[5m])
  )
)
increase(stove_db_retention_runs_deleted_total[1h])
```


## Idle-run expiry

The `run_expiry` worker checks at startup and every 60 seconds, using the existing worker success/error and duration metrics. Repository operation `expire_idle_runs` uses role `maintenance`. Logs report deleted-run counts and failed sweeps; failures retry on the next tick. A pod that skips because another holds the PostgreSQL sweep lock completes a successful no-op.

The timeout defaults to **3600 seconds (1 hour)** and is persisted through Admin’s **Idle run cleanup** setting; `0` disables expiry. PostgreSQL uses one additional short-lived connection per sweep, outside the query pools, with a 2-second connection timeout, 10-second statement timeout, and 2-second lock timeout. Each pass considers at most 1000 runs. Monitor worker freshness and error counts; network failures can outlast server-side SQL timeouts.
