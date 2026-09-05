# Burst delivery implementation status

The full burst-responsiveness plan is **not yet implemented or performance-validated**.
This change supplies database isolation, atomic ingestion and durable producer delivery.

## Implemented

- HTTP single-event, gRPC single-event and gRPC streaming ingestion admit work before
  scheduling a blocking task. The permit stays with the task if a request is cancelled.
  Saturation returns HTTP 503 or gRPC RESOURCE_EXHAUSTED.
- Preparation, assertion/trace correlation, sequence validation, domain writes,
  deduplication and durable publication share a transaction. PostgreSQL preserves
  both the per-run lock and the global publication-order lock.
- `POST /api/v1/events/batch` accepts protobuf `DashboardEventBatch` and returns
  `BatchAck`. `GET` on the same path reports supported limits. gRPC adds `SendBatch`;
  an older server responds UNIMPLEMENTED. Existing single-event APIs remain available.
- Batches contain 1–100 events from one run and at most 1,048,576 encoded bytes.
  Every event requires its original nonempty ID and positive sequence. A rejected
  batch rolls back all writes. Retries must retain IDs, sequences, payloads and order.
- SQLite retains one writer and separates interactive and replay connections.
  PostgreSQL has separate fixed-size writer, interactive and replay pools.
- Broadcast receivers share immutable stored event payloads. PostgreSQL notification
  wakeups coalesce into one slot. Relay pages run on blocking workers and yield between
  pages. SSE replay errors disconnect without advancing past an undelivered event.

Configuration follows CLI > environment > TOML/JSON file > default precedence:

| CLI | Environment | File key | Default | Range |
| --- | --- | --- | --- | --- |
| `--ingestion-capacity` | `STOVE_INGESTION_CAPACITY` | `ingestion_capacity` | 64 | 1–65536 |
| `--postgres-writers` | `STOVE_POSTGRES_WRITERS` | `postgres_writers` | 4 | 1–64 |
| `--postgres-readers` | `STOVE_POSTGRES_READERS` | `postgres_readers` | 4 | 1–64 |
| `--postgres-replay-readers` | `STOVE_POSTGRES_REPLAY_READERS` | `postgres_replay_readers` | 2 | 1–64 |

Pool counts exclude the PostgreSQL administration connection and notification listener.
Admission capacity counts running and queued ingestion operations, including batches.
Interactive reads still use the existing synchronous repository facade; moving every
async read caller behind bounded admission remains outstanding.

## Durable Kotlin producer

`DashboardEmitter.tryEmit` now saves the event and allocates its ID/sequence in one
local SQLite transaction before returning. The default directory is
`~/.stove/spool`; files are keyed by the ingestion endpoint, including the HTTP
base path. Producers using the same directory and endpoint share per-run ordering
and a single cross-process delivery lease. Each JVM shares the lease descriptor so
closing one producer cannot release another producer's POSIX lock.

```kotlin
DashboardSystemOptions(
  appName = "checkout",
  ingestion = DashboardIngestion.Http("https://stove.example.com"),
  spool = DashboardSpoolOptions(
    directory = Path.of("/persistent/stove-spool"),
    maxBytes = 1024L * 1024 * 1024
  )
)
```

The default disk budget is 1 GiB **per endpoint**. The database page limit reserves
more than half that budget for its rollback journal and filesystem overhead. Freed
pages are reused after acknowledgment. SQLite's page cache is bounded to 1 MiB per
producer connection; the sender holds at most 100 events/1 MiB, except an individual
larger event (maximum 8 MiB) which uses single-event delivery. The wakeup channel is
conflated and carries no evidence. All Kotlin monitor locks in the new delivery
code use `ReentrantLock.withLock`.

The sender groups events from the oldest pending run and waits at most 10 ms to
fill a partial batch. IDs and sequences stay unchanged on retry. HTTP 404/405 or
gRPC UNIMPLEMENTED disables batching for that emitter and enables single-event
fallback. Batch acknowledgments must match every original ID and sequence.

Transport failures retry with capped exponential backoff while the emitter is
running. `maxFailures` now limits consecutive failures **during shutdown**, instead
of disabling delivery and dropping events. Shutdown retains any pending records;
the next producer at the same endpoint/directory recovers them. Permanent validation
rejections stop delivery and are surfaced on emit/close; the evidence remains on
disk. Spool write failures or quota exhaustion throw `DashboardSpoolException`
without advancing the run sequence, so the caller can retry after restoring storage.
`deliveryStatus` on the emitter/system exposes pending count, bytes and the last
transport error. Keep the spool directory on persistent local storage for recovery.

## Scoped delivery and bounded replay

The existing stream remains `/api/v1/events/stream`. Opt in with
`?mode=scoped&run_id=RUN&test_id=TEST&after=CURSOR`; omit `test_id` for all evidence
in a run, or both identifiers for global lifecycle updates only. `Last-Event-ID`
takes precedence over `after`. Named `cursor` frames checkpoint inspected global
IDs in scoped mode. Gaps in global IDs are valid and do not imply missing history.
The SPA now handles named `resync` frames by invalidating its cache and reconnecting
from the supplied watermark. Selecting scoped mode in the SPA remains pending.

Replay uses at most 200 events and 1 MiB of serialized payloads per database page,
with lengths checked before loading bodies. Each replay operation has a 10,000-event,
8 MiB, five-second budget. Missing retained history, invalid future cursors and
oversized events request explicit resynchronization; read/send failures disconnect
without skipping undelivered data. SQLite V9 and PostgreSQL V4 retain deletion
watermarks even after all events for a run are removed. Pre-upgrade cursors at or
below existing history may require one conservative resynchronization.

Each pod shares an immutable payload cache limited to 2,000 events and 8 MiB.
Broadcast notices hold weak references, and clients replay when payloads have been
evicted. Each client has one queued SSE frame and a bounded send timeout. File-backed
SQLite retains separate writer, interactive and replay connections. In-memory SQLite
serializes them because it cannot provide WAL snapshots.

CLI/environment/file configuration adds `read_capacity` (64), `replay_capacity` (16)
and `stream_capacity` (64), exposed as `--read-capacity` / `STOVE_READ_CAPACITY` and
equivalent replay/stream names. Normal dashboard collection/detail reads and replay
acquire admission before spawning blocking work; saturation returns HTTP 503.
Administration, explorer and MCP admission still require follow-up.

## Collection pagination and committed record identities

All dashboard collection routes now accept `page=true`, `limit`, `cursor`, and
`search`: apps, runs, tests, collapsed/raw entries, snapshots, test/trace spans, and
run/test/ambient mock interactions and warnings. Existing requests continue returning
arrays; explicitly paginated requests return `{ items, next_cursor, watermark }`.
Pages default to 200 records and accept limits from 1 through 1,000. Pass the returned
cursor as a URL query parameter without modifying it. Cursor validation rejects a
changed collection, scope, search, or run metadata filter.

Queries limit records in the database and read the page and watermark in the same
SQLite transaction or PostgreSQL repeatable-read transaction. Apps sort by name,
runs by descending `(started_at, id)`, tests by ascending `(started_at, id)`, and
evidence by committed ID. Collapsed assertion cursors use the assertion's **first**
record ID, while its item contains the latest attempt and aggregate counts. A retry
therefore updates an existing assertion without moving it across page boundaries;
clients must reconcile updates after the page watermark, including records already
shown on earlier pages. SQLite run metadata filtering now happens in SQL for paginated
requests, so unmatched runs cannot consume the page limit.

Search covers app names; run IDs/apps/metadata; test/spec names; entry actions,
systems, results and evidence text; span operations/services/status/attributes/errors;
mock request/response or warning text; and snapshot systems/summaries/state bodies.
Search is a literal substring match using database `LOWER`, with SQL wildcard
characters escaped. Database-specific Unicode case-folding behavior still applies.

Paginated snapshots contain metadata and UTF-8 `state_bytes`, without `state_json`.
Fetch a body through
`GET /api/v1/runs/{run_id}/tests/{test_id}/snapshots/{snapshot_id}`; the route returns
null if that ID is unavailable in the requested scope. Legacy snapshot arrays still
include bodies. Snapshot bodies in scoped SSE and paginated SPA consumption remain
follow-up work.

New SSE publications use the IDs returned by the domain inserts in the committing
transaction. Retries still retain their original event IDs/sequences, and the global
SSE sequence remains separate from a record's ID. The browser now preserves distinct
snapshots with identical contents and reconciles committed IDs exactly, retaining
semantic fallback for older streams with temporary IDs.

## Flow and virtualization

Both Flow modes now construct/layout graphs in a persistent worker. A shared task
scheduler permits one active calculation and replaces only the latest pending input;
completed results for the current selection are published even when newer work waits.
Changing tests unmounts that worker, and changing mode/range rejects obsolete results.
Trace windows contain at most 1,000 spans. Timeline windows contain at most 500 entries,
leaving room for generated idle-gap nodes within the 1,000-node ceiling. Displayed
ranges and older/newer/follow-latest controls expose the remaining loaded evidence.
These controls still navigate the currently loaded arrays; server pagination will be
integrated in a later stage. Snapshot lanes remain outside this graph-node bound.

Fixed-height virtual lists avoid allocating a full row-layout array and position rows
arithmetically. Variable-height lists locate the visible range using binary search.

## Verification environment and limits

The environment is macOS x86_64. Initially Node/npm and system libpq were unavailable.
A temporary Node 24.0.0 runtime and Cargo's `vendored-postgres` feature allowed local
verification. Docker initially could not connect to `/var/run/docker.sock`; using
`DOCKER_HOST=unix:///Users/osoykan/.orbstack/run/docker.sock` subsequently enabled the
PostgreSQL acceptance tests. Those exposed a concurrent-retention race, fixed by
serializing each application's cleanup candidate read with an advisory lock.
PostgreSQL unit helpers requiring `STOVE_TEST_POSTGRES_URL` still return early without
that variable; actual PostgreSQL coverage is supplied by the Testcontainers suite.

SPA: 63 tests passed and the production build passed. SSE now handles explicit
resynchronization and caps queued payload bytes at 8 MiB as well as 2,000 events.
Kotlin: all 32 tests passed with the real-server test enabled; formatting checks passed.
Durable delivery coverage includes HTTP/gRPC batches, malformed acknowledgments,
legacy fallback, quota rollback, concurrent producers and abrupt JVM termination.
The real-server smoke test delivered 1,000 evidence records from ten Kotlin producers
to the compiled Rust server and verified exact materialization counts.
Rust verification includes 91 library tests, 54 API tests, nine MCP tests,
eleven real-server acceptance tests and the existing PostgreSQL load smoke test.
The scoped replay acceptance cases run on SQLite and across two PostgreSQL pods.
All eleven acceptance tests passed against the compiled server, including six SQLite
tests and five PostgreSQL tests (four exercise multiple pods). The batch
acceptance test also verified that an unchanged retry leaves exactly 99 test records
from a 100-event run/test batch. PostgreSQL acceptance additionally covers cross-pod lifecycle/SSE, concurrent retention,
batch retry through another pod and atomic rollback.
Focused Rust tests cover admission saturation, cancellation, batch rollback, retry
idempotency, size/run validation, HTTP negotiation and assertion correlation within a
batch.

There is no recorded before/after performance baseline, hardware load measurement,
sustained producer/browser load run, or evidence that the requested latency gates are met.

## Remaining plan work

- Bounded background retention, active-run protection and coordinated restartable jobs.
- Full queue/latency/replay/cleanup metrics and admission for administrative/MCP work.
- UI scoped subscriptions, coordinated recovery and retention-marker compaction.
- SPA page consumption, page-watermark reconciliation and lazy snapshot dialogs.
- Scoped SSE snapshot summaries and any additional server-side filter facets needed by the UI.
- Indexed UI reducers, sliced/coalesced processing, bounded caches, scope recovery
  and hidden-tab handling (virtualization and persistent bounded Flow are implemented).
- Keep the refactored module boundaries as the remaining features are implemented,
  then rerun combined verification.
- Baseline and final ten-producer/five-viewer sustained and burst workloads on both
  databases, including two PostgreSQL server instances and all failure scenarios.

Run the actual Kotlin/Rust smoke test after building the server:

```shell
STOVE_REAL_SERVER_BINARY="$PWD/server/stove-server/target/debug/stove" \
  ./gradlew :lib:stove-dashboard:test --tests '*DashboardRealServerTest'
```

`STOVE_REAL_SERVER_EVENTS` controls the number of evidence records per producer
(default 100; ten producers). This is a correctness smoke test, not the requested
sustained ten-minute reference-hardware benchmark.

### Prometheus diagnostics

`GET /metrics` now exposes bounded-cardinality backend operation pressure and
latency, commit/deduplication counters, SSE cache occupancy, relay progress/errors
and resynchronization attempts. See [METRICS.md](METRICS.md) for per-pod scraping,
PromQL examples, and measurement boundaries. Separate database connection-wait and ingestion/replay transaction histograms are
also available. Scheduler wait, producer spool and background cleanup instrumentation
remain follow-up work.

### Refactoring the implemented delivery path

The implemented paths now share a cancellation-safe blocking admission wrapper
and pagination option/cursor helpers. Metrics have separate observation, database
instrumentation and Prometheus exposition modules; scrapes copy metric snapshots
before formatting and group each metric family together. Delivery code reports
metrics through named operations instead of manipulating counters directly.

The Kotlin emitter separates delivery attempts from retry/shutdown policy, shares
transport cancellation and acknowledgement handling, and updates spool counters
once per acknowledgement transaction. Its locks remain `ReentrantLock.withLock`.
The SPA separates bounded queue state, evidence identity reconciliation and Flow
worker lifetime from the React views. Existing wire formats, cursor scopes,
publication locks and spool recovery rules remain in place. This refactor covers
implemented code; the outstanding functionality listed above remains outstanding.

Refactor verification: all 168 Rust tests (including SQLite and two-pod PostgreSQL
acceptance), all 31 regular Kotlin dashboard tests plus the real-server ten-producer
smoke test, and all 65 SPA tests passed. Rust Clippy and the production SPA build
passed. Biome reported no errors and three existing unused-suppression warnings in
untouched files. These checks do not replace the outstanding sustained-load gates.
