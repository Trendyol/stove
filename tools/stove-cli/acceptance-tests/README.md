# Stove CLI acceptance tests

These tests launch the compiled `stove` executable on ephemeral HTTP and gRPC
ports with temporary SQLite and PostgreSQL databases. PostgreSQL is started and
removed automatically through Testcontainers. The tests use public boundaries:
gRPC for ingestion, REST and MCP for reads and administration, and HTTP for the
embedded SPA. Direct PostgreSQL queries additionally verify the migrated column
and index definitions.

Run them from `tools/stove-cli`:

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
  retention, and administration against a disposable PostgreSQL container.

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
  cargo test --test acceptance real_cli_exposes -- --nocapture
```

Open the printed URL while the test is holding. The environment variable is
unset in CI, so normal acceptance runs never pause.
