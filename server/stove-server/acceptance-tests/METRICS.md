# Backend metrics

Every Stove HTTP server exposes `GET /metrics` in Prometheus text format. Scraping
reads process memory and the bounded SSE cache metadata; it does not query the
database. No run IDs, test IDs, evidence, SQL, or endpoint URLs become labels.
Metrics reset on process restart. The endpoint shares the server's HTTP listener
and network access policy.

Scrape each pod directly, not a load-balanced service address. Prometheus target
labels (`instance`, and your Kubernetes `pod` label) distinguish pods. With a
shared PostgreSQL database, commit counters count only writes performed by that
pod; relay/cache/subscriber metrics describe that pod's viewers. SQLite metrics
refer to the pod's local database. Do not sum relay cursors across pods.

Example for a local server (replace the port with your configured HTTP port):

```yaml
scrape_configs:
  - job_name: stove
    scrape_interval: 5s
    static_configs:
      - targets: ['localhost:8080']
```

Available metrics:

| Metric | Meaning |
| --- | --- |
| `stove_operations_in_flight{operation}` | Admitted work queued or running; operations are `ingest`, `read`, `replay` |
| `stove_operations_in_flight_bytes{operation}` | Encoded ingestion payload bytes queued or running; zero for reads/replay |
| `stove_operations_rejected_total{operation}` | Admission saturation; retryable overloads |
| `stove_operations_completed_total{operation}` | Completed admitted operations, including failures |
| `stove_operations_failed_total{operation}` | Failed admitted operations |
| `stove_operation_duration_seconds{operation}` | Histogram of admission-to-completion latency, including scheduler and connection waits |
| `stove_events_committed_total` | Newly committed ingestion events |
| `stove_events_duplicate_total` | Duplicate events acknowledged on retry |
| `stove_sse_cache_events`, `stove_sse_cache_bytes` | Bounded shared payload cache occupancy |
| `stove_sse_subscribers` | Local broadcast receivers |
| `stove_relay_cursor` | Last global durable event ID observed by this pod |
| `stove_relay_lag_ids` | Last observed durable watermark minus relay cursor; includes gaps/deleted IDs |
| `stove_relay_errors_total` | Failed relay polling attempts |
| `stove_relay_last_success_timestamp_seconds` | Unix timestamp of last successful relay page read, zero before first success |
| `stove_sse_resyncs_total` | Explicit client resynchronization attempts |

Operations count batches as one operation. Event counters count batch members.
Invalid batches rejected before admission do not increment operation counters.
Cancelled requests keep their in-flight accounting until blocking work finishes.
Read instrumentation covers the admitted dashboard read path, not every admin or
MCP operation. In-flight counts include both queued and executing operations.
Histograms currently combine scheduler/connection wait and database execution;
they are not isolated SQL transaction timing. Replay lag is sampled during polls
and can be stale when polling fails: inspect poll age and errors alongside lag.
Producer disk spool occupancy is local to producers and is not reported by this
backend endpoint. Cleanup timing will be added with the background cleanup job.

Useful PromQL:

```promql
# Committed events per second across pods
sum(rate(stove_events_committed_total[1m]))

# p95 admitted ingestion latency, per pod/instance
histogram_quantile(0.95,
  sum by (instance, le) (rate(stove_operation_duration_seconds_bucket{operation="ingest"}[5m])))

# Admission rejection rate by instance and operation
sum by (instance, operation) (rate(stove_operations_rejected_total[1m]))

# Seconds since a successful relay poll (zero timestamp means not initialized)
time() - stove_relay_last_success_timestamp_seconds

# Viewer recovery pressure
sum by (instance) (rate(stove_sse_resyncs_total[5m]))
```

Database breakdown:

- `stove_database_duration_seconds{operation}` is a histogram with fixed labels:
  `sqlite_write_wait`, `sqlite_read_wait`, `sqlite_replay_wait`,
  `sqlite_explorer_wait`, `postgres_write_wait`, `postgres_read_wait`,
  `postgres_replay_wait`, and `postgres_explorer_wait` measure connection
  acquisition. SQLite measures its connection mutex; PostgreSQL measures pool
  checkout (or the explorer mutex).
- `sqlite_ingest_transaction`, `postgres_ingest_transaction`,
  `sqlite_replay_transaction`, and `postgres_replay_transaction` measure the
  complete transaction after acquiring a connection, including commit or rollback.
  Ingestion includes preparation, correlation, sequence checks, domain writes,
  durable publication, database/advisory lock waits and current retention work.
- `stove_database_operations_in_flight`, `_completed_total`, and `_failed_total`
  share those labels. Wait operations in flight indicate pending acquisition;
  transaction operations in flight indicate executing transactions. The database
  `_in_flight_bytes` and `_rejected_total` series are zero: bytes are measured at
  ingestion admission, and rejection occurs before database acquisition.

These distinguish scheduler/admission-to-worker delay, connection contention and
transaction time without collecting SQL or evidence. They do not provide individual
SQL statement timings or the database engine's internal lock/I/O statistics.
Interactive reads currently have total operation timing and connection-wait timing,
not a separate per-statement execution histogram. Do not subtract independently
computed p95s to estimate execution latency.

```promql
# p95 PostgreSQL writer pool wait per pod
histogram_quantile(0.95, sum by (instance, le) (
  rate(stove_database_duration_seconds_bucket{operation="postgres_write_wait"}[5m])))

# p95 SQLite ingestion transaction, after acquiring its writer
histogram_quantile(0.95, sum by (instance, le) (
  rate(stove_database_duration_seconds_bucket{operation="sqlite_ingest_transaction"}[5m])))

# Current waits/executing transactions, individually by operation
stove_database_operations_in_flight
```
