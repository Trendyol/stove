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

## MCP failure-evidence benchmark

The standalone harness feeds assertion mismatches, database exceptions, unmatched
mock requests, a 400-span timeout trace, and a 1,200-record snapshot into the public
protobuf HTTP endpoint. It measures the release server's MCP responses using a
temporary SQLite database. It clears inherited `STOVE_*` settings in the child
process and removes its database and server process when finished.

Install `protoc` on your path, then run from `server/stove-server`:

```shell
cargo build --release --locked --bin stove
python3 -m venv target/mcp-benchmark-venv
target/mcp-benchmark-venv/bin/pip install protobuf==7.36.1 tiktoken==0.14.0
target/mcp-benchmark-venv/bin/python scripts/benchmark-mcp.py
```

Use `--suite diagnosis` to benchmark `stove_diagnose` alone: every failure under
each budget, a complete five-test run in one call, and selection by CI metadata.
The harness also follows returned continuations with two tests per page and
requires every failure to appear exactly once in the same run, with each seeded
cause present. The wide catalog case supplies no JSON pointer to the diagnosis
tool; the server must find its recorded diagnostic field itself. This verifies
evidence extraction and traversal, not model reasoning or the correctness of a
proposed code fix.

Diagnosis cases also require the failed report entry and failed trace target in
the first response, enforce the five-entry/eight-span context limits, and verify
omission counts. See the [context benchmark](../benchmarks/mcp-diagnose-context-2026-09-08.md)
for the added token cost and local latency measurements.

The reference run used `protoc 36.1`; the Python protobuf runtime must support your
installed compiler. The tokenizer downloads its encoding on first use. No model
API or Docker daemon is needed.

Defaults: 60 measured requests per case after three warmups, then two 240-request
workloads with eight concurrent clients. Override `--samples`, `--requests`,
`--concurrency`, `--binary`, or `--output` as needed. The output directory contains
`results.json` (raw timings, percentiles, sizes, token counts, error probes), sample
responses, and `server.log`. It defaults to the ignored `target/mcp-benchmark`.

Latency ends after reading the full HTTP body and excludes client JSON parsing and
tokenization. Token counts use `o200k_base`; text fallback and complete wire
envelope counts are separate because clients may expose different content to the
model. The pretty JSON comparison measures formatting only. The raw REST bundle
comparison measures selective retrieval versus fetching all four evidence kinds,
not equal-information compression. Seeded cause-marker checks verify evidence
presence, not an LLM's diagnostic ability. The wide-record pointer is known to the
harness; this does not test whether an agent can discover it unaided.

Error probes cover invalid arguments, limits, budgets and trace views, missing
selectors and runs, unknown tools, malformed calls, and malformed JSON. Any wrong
error classification, HTTP failure, text/structured mismatch, or synthetic-secret
leak fails the run. Latencies are observations, not portable CI timing thresholds.

See [the recorded results](../benchmarks/mcp-2026-09-07.md) for the measured
improvements and remaining limits. This complements the PostgreSQL load suite;
it does not measure shared-server scale, remote networking, or model latency.

For a manual browser pass against the fixture produced by the first test, run:

```shell
STOVE_ACCEPTANCE_BROWSER_HOLD_SECONDS=300 \
  cargo test --test acceptance real_server_exposes -- --nocapture
```

Open the printed URL while the test is holding. The environment variable is
unset in CI, so normal acceptance runs never pause.
