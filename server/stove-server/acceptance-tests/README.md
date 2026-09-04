# Stove Server acceptance tests

These tests launch the compiled `stove` executable on ephemeral HTTP and gRPC
ports with temporary SQLite and PostgreSQL databases. PostgreSQL is started and
removed automatically through Testcontainers. The tests use public boundaries:
gRPC for ingestion, REST and MCP for reads and administration, and HTTP for the
embedded SPA. Direct PostgreSQL queries additionally verify the migrated column
and index definitions.

Refinery owns migration history in `refinery_schema_history`, while Diesel owns
normal persistence. The tests intentionally start with empty databases; legacy
databases using the removed `schema_migrations` table are not supported.

Run them from `server/stove-server`:

```shell
cargo test --test acceptance
```

A Docker-compatible daemon must be running. PostgreSQL coverage is mandatory;
the suite reports a Testcontainers startup failure instead of skipping it.

The suite covers:

- complete run/test/evidence ingestion over gRPC;
- REST reads and exact-subset dynamic metadata filters;
- MCP initialization, discovery, metadata filters, and agent drill-down calls;
- non-local `Host`/`Origin` MCP clients;
- the embedded SPA and its compiled assets;
- default and runtime retention with overlapping active runs;
- purge preview counts, exact IDs, active-run protection, purge, and clear;
- PostgreSQL migrations, JSONB storage and GIN indexing, metadata filters,
  retention, administration, schema discovery, SQL mutations, and bounded
  explorer queries against a disposable PostgreSQL 18 container;
- two simultaneously started server pods sharing PostgreSQL, including alternating
  and concurrent ingestion, ordered cross-pod SSE without duplicate frames,
  `Last-Event-ID` replay, shared retention, concurrent pruning, and continuation
  after one pod stops.

Run the PostgreSQL load test separately:

```shell
cargo test --test load -- --nocapture
```

It seeds 50,000 runs, requires PostgreSQL to select the JSONB GIN index, and
mixes concurrent REST metadata searches, run-list and app-list dashboard reads,
admin reads, SPA loads, and MCP metadata searches. The default p95 latency
budget is 2 seconds. The workload can be tuned with
`STOVE_LOAD_TEST_RUNS`, `STOVE_LOAD_TEST_REQUESTS`,
`STOVE_LOAD_TEST_CONCURRENCY`, and `STOVE_LOAD_TEST_P95_MS`.

For a manual browser pass against the fixture produced by the first test, run:

```shell
STOVE_ACCEPTANCE_BROWSER_HOLD_SECONDS=300 \
  cargo test --test acceptance real_server_exposes -- --nocapture
```

Open the printed URL while the test is holding. The environment variable is
unset in CI, so normal acceptance runs never pause.
