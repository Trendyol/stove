# Monitoring a deployed Stove server

Stove uses the Rust `prometheus` crate for its registry, counters, histograms and text encoder. It exposes Prometheus text metrics at **`GET /metrics`** on its existing HTTP port (4040 by default). Collection starts automatically for SQLite and PostgreSQL. No additional service or admin UI is required. Configure Prometheus with [prometheus.yml](prometheus.yml), adjust the target addresses, and load [alerts.yml](alerts.yml). The thresholds are starting points; tune them against your workload.

With `public_url = "https://stove.example/observe"`, both `/observe/metrics` and `/metrics` work, matching the server's existing prefix handling. Use your deployment's usual ingress authentication/network access configuration for the endpoint.

```sh
curl -fsS http://localhost:4040/metrics
```

**Scrape each server/pod directly**, using static targets or your Prometheus service discovery. A load-balanced target alternates unrelated process counters. `stove_instance` identifies the configured `STOVE_INSTANCE_ID` (or `HOSTNAME`, falling back to `stove`), PID, and a unique process generation. `backend` is `sqlite` or `postgresql`. Prometheus additionally supplies its usual `job` and `instance` labels. A process restart resets its counters; Prometheus retains history.

Operations and acquisition waits update counters/histograms as they happen. Capacity is sampled every **10 seconds**; role probes and diagnostics run every **30 seconds**. Scraping reads memory only, including when the database is unavailable. Failed diagnostics retain their previous values and expose `diagnostics_up=0`; always pair diagnostic values with their age. There is one diagnostic worker per repository, and a stalled probe cannot spawn an accumulating queue of SQL workers.

## Metrics

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

Import [grafana-dashboard.json](grafana-dashboard.json) using **Grafana → Dashboards → New → Import → Upload dashboard JSON file**. After importing, select your Prometheus data source and job at the top, then filter by backend and instance. It uses built-in Grafana panels and requires no extra plugins.

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
