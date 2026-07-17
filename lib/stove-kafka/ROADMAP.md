# stove-kafka Capability Roadmap

Working document for improving `lib/stove-kafka`. Captures the initial ideas, the decisions that shaped them, and implementation progress.

## Goals

Make stove-kafka the strongest Kafka e2e-testing surface on the JVM by improving three things in order:

1. **Foundation** — event-driven internals and test-scoped isolation, so everything built on top is fast, parallel-safe, and debuggable.
2. **Diagnostics** — feed richer failure evidence into the existing reporter → dashboard (stove-cli) → MCP pipeline.
3. **Fidelity & assertions** — close publishing gaps and add assertion power, always based on positive evidence.

## Priority order

Current sequence: **D1 (event-driven foundation) ✅ → D2 (observer isolation and wire compatibility) ✅ + B0 (partition correctness) ✅ → B1 (raw/tombstone publishing) ✅ → C (diagnostics) → A (assertions, deferred — discuss before starting)**. Semantic observation envelopes and asynchronous batching were reviewed and deliberately not adopted; the existing JVM/Go unary protocol remains the single contract. E-theme differentiators (chaos, schema registry, record & replay) are later bets, picked by audience.

---

## Theme D — Foundation and observer correctness ✅

| Item | Status | Notes |
|---|---|---|
| Flow-based `MessageStore` | ✅ | `version: StateFlow<Long>` bumped on every record; public `events: SharedFlow<StoveMessageEvent>`; store-backed replay-then-live flows avoid subscribe races without making the lossy event stream a source of truth. |
| Signal-driven waits | ✅ | `waitUntilConditionMet` / `waitUntilCount` and the three `peek*` loops no longer poll with `delay(50/100)`; they suspend on the store's version signal. |
| Test-scoped assertions | ✅ | `waitUntilConsumed/Published/Failed/Retried` and `throwIfFailed/throwIfRetried` filter by the current test id (from `TraceContext`), fail-open (see decisions). Fixed a latent bug: another test's failed message with matching content could fail the current test. |
| Test-scoped failure dumps | ✅ | Timeout `AssertionError`s embed `MessageStore.dump(testId)` — only the current test's messages plus a `N message(s) from other tests hidden` count — instead of everything observed since the suite started. |
| Bonus fixes | ✅ | `firstNotNullOf { it.key == "testCase" }` logging crash on header-less messages (source of silently swallowed gRPC UNKNOWN errors in the bridge); removed pointless `runBlocking` wrappers in record paths. |
| Flow-idiom refactor | ✅ | Replay-then-live record flows on `MessageStore` (`consumedRecords()` etc.); all waits collapsed into one `awaitRecords` helper built on `version.first { }` — no `while(true)` loops, no manual version bookkeeping; `peek*` return the matched record; shared `matches()` helper removed the deserialize+condition boilerplate; `shouldBeRetried` now reports like the other assertions. |
| Ad-hoc consumer thread-safety fix | ✅ | The old `consumer()` closed and committed the `KafkaConsumer` concurrently with the poll loop (KafkaConsumer is not thread-safe) and polled with a redundant `delay(100)`. Poll, callback, exact-offset commit, and close are now serialized in one coroutine on `Dispatchers.IO`; a monotonic deadline stops new callbacks without cancelling one halfway through. |
| Per-system bridge runtime | ✅ | Every Kafka system owns its observer scope, internal endpoint, serde registration, server, and authoritative client properties. Closing one keyed system no longer cancels the process-global scope used by every other system. Keyed systems using the default get independent observer ports, and that default intent is captured when options are constructed rather than re-inferred from a mutable global later. |
| Single observer wire contract | ✅ | The original `StoveKafkaObserverService` remains the only service: health check plus `onConsumedMessage`, `onPublishedMessage`, `onCommittedMessage`, and `onAcknowledgedMessage`. JVM interceptors can use per-system client properties; existing single-system applications and the published Go bridge keep using `STOVE_KAFKA_BRIDGE_PORT`. No V1/V2 pair or legacy adapter exists because the existing contract was preserved rather than replaced. |
| Semantic observation envelope | ↩ not adopted | The proposed envelope added richer stages and group identity, but replacing the wire messages broke the independently published Go client and every application using the documented interceptor/env setup. Richer evidence must be introduced only with an explicit cross-language migration plan and a demonstrated assertion need. |
| Asynchronous batching/finalization | ↩ not adopted | Batching was unnecessary for the current testing workload, added a prompt-cancellation loss path, and made close completeness part of assertion correctness. Observer callbacks therefore forward the message conveyed by the Kafka bridge directly through the existing unary calls. |
| Executor rejection semantics | ✅ | Coroutine-backed executors now reject work after shutdown/cancellation instead of accepting it and silently dropping the task. |
| Lifecycle cleanup | ✅ | Producer, admin client, observer server, per-system bridge runtime, and Kafka runtime are closed independently; one cleanup failure no longer skips every remaining resource. The producer closes while the observer is still available for final acknowledgement callbacks. |

Verification status for D1/D2/B0: all 168 Kafka tests pass across the provided, embedded, and container modes (56 in each mode); the Ktor example passes 5/5 using an explicit non-default observer port with interceptor-only application wiring; Go bridge unit suites pass for the shared bridge, Sarama, Franz, and Segmentio; and the Go showcase passes end-to-end against the locally published JVM artifact with all three Kafka clients. `apiCheck` and `spotlessCheck` pass after API regeneration. Detekt is disabled by the root build on JDK 25+.

### D2 — protocol compatibility decision ✅

The observer is internal to Stove's test architecture, but its wire contract crosses process and release boundaries. In particular, `go/stove-kafka` is independently published and the Go process/container recipes connect to the JVM observer through `STOVE_KAFKA_BRIDGE_PORT`. That makes an in-place protocol replacement a breaking change even when the Kotlin server and interceptor ship together.

The adopted design is therefore intentionally small:

1. keep the original message DTOs, RPC names, and environment variable unchanged;
2. pass each Kafka callback's message directly through the matching unary RPC, without strict bridge-identity validation or mutation of application records;
3. use per-system port/serde properties when Stove creates clients, while retaining the environment/system-property fallback for existing and non-JVM applications;
4. keep observer state internal—no identity-keyed endpoint getter on `KafkaExposedConfiguration`;
5. require an explicit cross-language compatibility plan before changing this wire format in the future.

### D3 — recorder/assertions composition ✅

The trait-style sharing (`CommonOps`/`MessageSinkOps`/`MessageSinkPublishOps` interfaces mixed into a single `StoveMessageSink`) was replaced by composition split along the two real consumers:

- **`KafkaRecorder(store, topicSuffixes)`** — the write surface. The gRPC observer server depends on this class only; the store's raw record methods stay internal behind it.
- **`KafkaAssertions(store, serde, ...)`** — the signal-driven assertion engine (waits, deserialize-matching, test-id scoping, dumps), with every helper genuinely `private`. Transport-agnostic by construction.

Why: the interfaces had exactly one implementer and zero polymorphic consumers, forced helpers (`awaitRecords`, `deserializeCatching`, `matches`) into the *public* API because interface members cannot be private, and required a dead `adminClient` dependency in the contract (the unit tests needed a reflection `Proxy` to fabricate an `Admin` that must never be called). All of that is gone; the public API surface shrank to four waits + four records, `KafkaSystem`'s store/recorder/assertions became eager `val`s (no lifecycle ordering), and the Proxy hack was deleted from the tests.

This became the reuse seam for the shared foundation below: integrations adapt their native records at the transport edge and use the same store and assertion engine.

### D4 — shared standalone/Spring Kafka foundation ✅

Core messaging now owns the transport-neutral `KafkaRecord` contract, signal-driven `KafkaMessageStore`, test-id/baggage scoping, `KafkaAssertions`, and assertion reporting under `com.trendyol.stove.messaging.kafka`. Both integrations reuse it directly:

- `lib/stove-kafka` adapts the existing Wire/gRPC records and enables commit-aware consumption plus retry/error-topic classification;
- `starters/spring/stove-spring-kafka` adapts Spring producer/listener callbacks and enables Spring's success-versus-failure conflict check;
- both retain their framework-native message facades and reporting snapshots, while waits, timeout dumps, replay flows, cancellation handling, and matching semantics execute through one implementation.

The duplicate Caffeine stores, polling loop, deserialization helpers, assertion reporting blocks, and global Spring failure dumps were removed. Keeping the shared foundation in core messaging lets Spring Kafka reuse it without depending on the standalone module's Wire, embedded-Kafka, and bridge stack or publishing a single-purpose intermediary artifact.

## Theme C — Diagnostics & observability ⏳ next

| Item | Status | Notes |
|---|---|---|
| Near-miss diagnostics | ⬜ | On assertion timeout, attach same-type candidates that failed the condition (field-level diff) to `ReportEntry.failure`. Console shows closest miss; dashboard shows all; MCP gives agents "3 candidates, closest differed in `amount`" instead of "timeout". Best value-per-effort item. |
| Message choreography view | ⬜ | stove-cli dashboard swimlane per test: trigger → publish → consume → retry → DLT → commit, correlated with OTel spans (same sqlite store, same test-id keying). Mermaid export nearly free. |
| Message-leak warnings | ⬜ | Events published that nothing consumed, or matches arriving after the assertion window — surfaced as dashboard timeline *warnings*, never test failures (see decisions). |
| Consumer-group lag panel | ⬜ | Live `Admin`-fed panel: group state, per-partition lag, rebalance events on the timeline. Assertion API can follow once the data proves useful. |
| Cross-run trend analytics | ⬜ | `~/.stove-dashboard.db` persists across sessions: track assertion latency vs. timeout per test per run; flag assertions trending toward their timeout (flakiness early warning). |

## Theme B — Publishing fidelity ✅

| Item | Status | Notes |
|---|---|---|
| Fix `partition: Int = 0` default | ✅ | `publish` now uses the ABI-safe `PARTITION_BY_KEY = -1` sentinel and constructs a record with no explicit partition, allowing Kafka's configured partitioner to decide. Explicit non-negative partitions remain supported and invalid negative values fail early. |
| `publishTombstone(topic, key)` | ✅ | Publishes a null-value record for the (mandatory) key. `StoveKafkaValueSerializer` maps null → null so Kafka stores a genuine tombstone, not empty bytes; the bridge observes it as an empty payload, so `peekPublishedMessages` can assert on it. `StoveKafkaValueDeserializer` is now null-safe for consuming tombstones. |
| `publishRaw(topic, bytes)` | ✅ | Sends bytes to the wire unchanged: `StoveKafkaValueSerializer` passes `ByteArray` through, mirroring the bridge's observation-side passthrough, so observed evidence equals wire bytes. Poison-pill/DLT scenarios are a one-liner. Both new methods share `publish`'s record construction (headers, trace injection, partition sentinel, reporting). Custom value serializers must handle null/`ByteArray` to use these. |

B1 verification: 64 Kafka tests pass in all three modes (container, embedded, provided), including tombstone and raw-bytes round-trips through publish-observation (`peekPublishedMessages`) and an ad-hoc consumer; `apiCheck` passes with additive-only API changes (the serializer/deserializer nullability changes don't alter JVM descriptors) and `spotlessCheck` passes.

B1 parity in `starters/spring/stove-spring-kafka`: same `publishTombstone`/`publishRaw` surface. Tombstones go through the application's own `KafkaTemplate` (so key bytes match regular `publish`; standard serializers map null → null), while raw bytes use a dedicated Stove-owned producer (String keys, `ByteArraySerializer` values) because the application's value serializer would re-encode them. The observation path (`serializeIfNotYet`, `toMetadata`) is now null-safe, recording tombstones as empty payloads like the standalone bridge. Because the typed `shouldBe*` assertions require deserialization, the starter also gained `peekPublished/Consumed/FailedMessages` over the raw observed `MessageProperties` — the assertion surface for tombstone/poison-pill evidence. Verified with starter unit tests plus a spring-example e2e round-trip; both starter API dumps changed additively only.

## Theme A — Assertion power ⏸ deferred (discuss before starting)

- Count / exactly-once assertions: `shouldBePublished<T>(times = 3)`, `shouldBeConsumedExactly<T>(1)` (idempotency proofs with positive evidence).
- Per-key ordering assertions: `shouldObserveInOrder(key) { first<A>{}; then<B>{} }`.
- Anchored absence: `afterConsuming<A> { nothingPublishedTo("topic") }` — see decisions.
- Retry/DLT journey DSL: `shouldFollowRetryFlow<T> { retriedTimes(3); thenLandsInDlt() }`.

## Theme E — Differentiators (later bets)

- **Chaos toolkit**: Toxiproxy layer — broker latency, connection cuts, mid-test broker death, forced rebalances; chaos events on the dashboard timeline.
- **Schema Registry add-on**: registry container + Avro/Proto serdes + schema-compatibility assertions.
- **Record & replay**: captured choreography as replayable fixtures (falls out of the choreography view data).
- **Modern embedded runtime**: KRaft-native / `apache/kafka-native` instead of the Scala `EmbeddedKafka` path.

---

## Review findings — D2 compatibility review and resolution (2026-07-15)

Process: two independent reviewers (one scoped to concurrency/lifecycle/transport, one to compatibility/API/semantics), findings reconciled and re-verified against source before documenting. The critical findings caused the semantic/batched protocol to be rolled back rather than patched forward. Severities: only timeouts/false evidence and user breakage rank critical/major; fail-safe imprecision ranks minor.

### Critical

**F1 — Resolved: unconfigured interceptors were rejected, so plain stove-kafka applications recorded nothing.**
Strict bridge-identity validation was removed with the semantic envelope. The interceptor again accepts the existing setup—`interceptorClass` plus `STOVE_KAFKA_BRIDGE_PORT` for an out-of-process application—and simply forwards what the bridge observes. Per-system bridge properties remain an internal routing improvement for clients Stove creates. The previously failing Ktor example now passes 5/5 without custom bridge-id wiring.

**F2 — Resolved: the published Go bridge spoke the protocol that had been removed.**
The original unary service—not a separately named legacy service—has been restored exactly. `go/stove-kafka` continues calling `onConsumedMessage`, `onPublishedMessage`, `onCommittedMessage`, and `onAcknowledgedMessage`, and continues reading `STOVE_KAFKA_BRIDGE_PORT`; no Go regeneration or lockstep release is required. Go unit suites and the process showcase pass with Sarama, Franz, and Segmentio against the current local JVM artifact.

### Major

**F3 — Resolved by removal: prompt cancellation could lose an observation in the batching loop.**
`KafkaObservationTransport`, its bounded channel, retry/finalization state, and batching configuration were removed. Kafka callbacks synchronously invoke the matching unary observer call as they did before, so there is no queue receive/timeout cancellation window and no close-time high-watermark whose correctness assertions depend on.

**F4 — Bridge setup regression resolved; partition-default release note remains.**
Existing documentation is valid again because interceptor-only and env-var wiring were preserved. No semantic/batching migration is required. The `publish` default-partition correction is behavior-visible and still needs a release-note callout before release.

### Minor

- **F5 — No longer applicable.** Source sequences and close finalization were removed with the semantic transport.
- **F6 — `publish` default-partition change is behavior-visible.** Null-key publishes to multi-partition topics now spread via the sticky partitioner instead of pinning to partition 0; user tests asserting partition metadata or cross-message ordering can start flaking. Source/binary compatible; needs a release-note callout.
- **F7 — Resolved.** The interceptor no longer adds `X-Stove-Observation-Id` or otherwise mutates application records.
- **F8 — Existing semantics retained.** `shouldBePublished` continues to mean that the producer interceptor observed `onSend`; changing it to acknowledgement evidence is a separate assertion-contract decision, not smuggled into the wire refactor.
- **F9 — Resolved.** Whether a bridge port used the compatibility default is captured when `KafkaSystemOptions` is created, so later mutation of `stoveKafkaBridgePortDefault` cannot make a keyed system reuse a stale default port.
- **F10 — Resolved by API removal.** `KafkaExposedConfiguration.bridge` and the identity-keyed configuration registry were removed; routing endpoints remain internal.
- **F11 — Resolved.** Coroutine-backed executors throw `RejectedExecutionException` after shutdown/cancellation instead of silently dropping work.
- **F12 — Resolved.** Health-check and interceptor client handles now cancel outstanding calls and evict their OkHttp connection pools on close; the shared coroutine scope is cancelled separately by its owner.
- **F13 — No longer applicable.** Batch replies and duplicate-count diagnostics were removed.

### Verified sound (load-bearing checks that passed both reviews)

`KafkaSystem.close()` ordering keeps the observer alive through producer close and isolates per-step failures; per-system scopes prevent one keyed system from cancelling another; `isCommitted` keeps the original next-offset comparison; test-id scoping works on the original unary DTOs; error/retry suffix classification is unchanged; `configuration()` strips user-supplied bridge keys before appending authoritative internal routing values; and the generated Go method names and JVM proto service now match exactly.

### Re-review of the corrections (2026-07-15, second pass)

Independently verified: the proto, `MessageSinkOps`, `KafkaExposedConfiguration`, and `build.gradle.kts` are byte-identical to the last commit (wire contract truly restored, not merely similar); the interceptor's port fallback chain is `stove.kafka.bridge.port` → `STOVE_KAFKA_BRIDGE_PORT` env → system property → shared default; no bridge-identity validation and no record mutation remain; the Go showcase's `env("STOVE_KAFKA_BRIDGE_PORT", …)` hand-off still lines up with the server's default port; tombstone-safe `serialize(null)` is a quiet improvement that pre-stages B1. Public `GrpcUtils` and `StoveKafkaObserverGrpcServer` visibility is preserved. Re-ran verification: full lib suite green in all three modes, `apiCheck` and `spotlessCheck` pass.

New findings from the second pass, all resolved:

- **F14 — Resolved.** A non-keyed system exposes its actual bound observer port through the established system-property fallback, including an explicit non-default `bridgeGrpcServerPort`. Close restores the previous value only if the application has not replaced it in the meantime. Keyed systems continue using per-system client properties because a process-global fallback is ambiguous. The Ktor example now exercises this exact custom-port/interceptor-only path.
- **F15 — Resolved with F12.** Health-check and interceptor handles cancel outstanding calls and evict their connection pools before their owning scope is cancelled, so no straggling OkHttp task is submitted to a rejected executor during normal shutdown.
- **F16 — Resolved.** `obtainExposedConfiguration` returns the provided configuration directly; the identity-registry-era `.copy()` is gone.
- **Verification count corrected.** The suite now contains 56 tests per Kafka mode (168 total).

---

## Decisions log

**No plain negative assertions.** `shouldNotBeConsumed<T>` over a time window was rejected: absence is ambiguous — correct suppression by the app is indistinguishable from a broken interceptor, misconfigured client, or dead bridge. Validating arrival is more valuable than asserting non-arrival.

**Ambiguous signals go to the dashboard, not assertions.** Evidence that is suggestive but not conclusive (message leaks, late matches) is surfaced as dashboard warnings, never as test failures. Assertions stay reserved for positive evidence.

**Absence is only assertable anchored to proven liveness.** The acceptable form of "nothing happened" requires a liveness anchor first: a consumed-and-committed trigger, or a completed trace (stove-tracing's `shouldNotContainSpan` is sound precisely because the span tree proves the pipeline ran). The planned construct makes the anchor mandatory so the unsound half cannot be written alone, and failures distinguish "pipeline never proved alive" from "message absent".

**Test scoping is fail-open.** Applications may publish with no propagation at all (no OTel agent, no manual header copying), so untagged messages always match every test — behavior identical to before scoping existed. A message is excluded only when *provably* tagged with a different test id. Tags are only ever written by Stove, via two transports: the `X-Stove-Test-Id` Kafka header (injected by Stove's `publish`, carried into the app's consumed records by Kafka itself, no OTel needed) and the W3C `baggage` header (`stove.test.id`, present on app-published messages only when the OTel agent propagates context). Extraction is liberal: case-insensitive header keys, percent-decoded baggage values, malformed baggage degrades to "untagged" rather than excluding.

**StateFlow is the wait primitive.** `MessageStore.version` is a `StateFlow<Long>` bumped on every record, so `version.first { condition() }` evaluates the condition immediately and again after observed version changes. Rapid changes may be conflated, but every evaluation re-queries the latest store state, so records cannot be missed when predicates are pure and the matching set is append-only. Record streams (`consumedRecords()` etc.) are built the same way: collect `version`, re-query the store, dedup by id — replay-then-live with no subscribe race.

**Store stays the source of truth.** The `events` SharedFlow (bounded buffer, drop-oldest) is a live-tail surface; assertions and record flows always re-read the caches, so slow collectors can never cause missed assertions.

**Only timeouts are assertion failures.** `awaitRecords` uses `withTimeoutOrNull`; an exception thrown by a user condition propagates as itself instead of being mislabeled `GOT A TIMEOUT` (the old `runCatching` swallowed everything). Timeout errors report expected vs. found match counts plus the scoped dump.

**`atLeastIn` name kept, for now.** Renaming the public assertion parameter (e.g. to `within`) is binary-compatible but source-breaks every caller using named arguments, and Kotlin offers no deprecation path for parameter names. Deferred to a deliberate flag-day decision; internals use `within`.

---

## Technical notes: how the foundation is built, and why

### The waiting problem, and why `StateFlow` solves it

Every Kafka assertion is fundamentally "suspend until the store satisfies a condition, or time out". The pre-refactor implementation polled: `while (!condition()) delay(50)`. Polling has three costs — up to 50–100 ms of added latency per assertion (quantization), constant wake-ups that do nothing, and a busy loop per concurrent assertion (the "lots of messages" test runs 100 at once).

The naive event-driven replacement — "wait for the next store change, then re-check" — has a classic **missed-wake-up race**: if a record arrives *between* the condition check and the suspension, the waiter sleeps until the *next* record, which may never come. The first fix was manual: capture a version counter *before* checking, and suspend on `version != captured` (a record arriving in the gap advances the version, so the await returns immediately). That works, but every call site has to repeat the capture-check-await choreography correctly.

`StateFlow` makes the race unrepresentable instead of merely handled. `MessageStore.version` is a `MutableStateFlow<Long>` incremented on every record, and `StateFlow`'s contract is: a collector **always receives the current value first**, then every subsequent change. So

```kotlin
store.version.first { matching().size >= count }
```

evaluates the condition immediately at subscription (covering records that already arrived) and then after observed version changes. There is no gap in which a record can slip by, no version bookkeeping at the call site, and no explicit loop — `first` performs the collection loop. `StateFlow` may conflate rapid changes, so several inserts can intentionally result in one re-check against the latest store contents.

Two properties make this sound here:

- **Matching sets are monotone for pure predicates.** The store is append-only for the duration of a wait (caches are only cleaned at system close), so once a deterministic condition over store contents becomes true it stays true. User predicates are therefore expected to be side-effect-free. `StateFlow` conflates rapid updates — under a burst, a collector may observe version 5 and then 9, skipping 6–8 — but conflation can only *batch* re-checks, never skip a stable satisfying state, because the condition re-queries the live store rather than inspecting the version number.
- **The store is the source of truth, not the signal.** The condition closure re-reads the caches on every evaluation. Even if every intermediate signal were dropped, one evaluation against the final state gives the right answer.

`version` is a monotone counter rather than, say, a `StateFlow<List<Message>>` because state-carrying flows would need a copy of the collections per update (or expose mutable state to collectors), and `StateFlow` equality-conflates — two different store states could compare equal with careless value semantics. A scalar counter keeps the signal small, never equals its predecessor, and pushes the querying where it belongs: the caches.

### `awaitRecords`: one helper instead of four wait shapes

`waitUntilConsumed/Published/Failed/Retried` differed only in *which* collection they query and *how many* matches suffice. That variance is exactly two parameters, so the machinery collapsed into:

```kotlin
suspend fun <T> awaitRecords(within, subject, testId, count = 1, query, predicate): Collection<T>
```

Design choices inside it:

- **`withTimeoutOrNull` instead of `runCatching` + `withTimeout`.** The old code caught *every* exception and reported it as `GOT A TIMEOUT`, so an NPE inside a user's condition lambda was indistinguishable from a slow consumer — a debugging trap. `withTimeoutOrNull` encodes "timeout" as `null` in the type; anything thrown by the predicate propagates unchanged. The error path is reached only by genuinely running out of time, and its message can therefore honestly report expected vs. found counts plus the test-scoped dump.
- **`count` folded in.** The old `waitUntilRetried` nested one wait inside another (`waitUntilConditionMet` inside `waitUntilCount`), each with its own `atLeastIn` — worst-case 2× the caller's timeout, and two copies of timeout error handling. `first { matching().size >= count }` is the same statement with `count = 1` as a degenerate case.
- **The predicate re-runs against the whole collection on each observed signal.** This is deliberate O(n·signals) simplicity: n is bounded by messages observed in a test run and conditions are normally cheap deserialize + field checks. Deserialization results are not cached, so an incremental or memoized path remains a future optimization if high-volume tests show this scan becoming material.

The companion `matches(payload, metadata, clazz, condition)` helper exists because the block "deserialize; if it parsed, wrap in `SuccessfulParsedMessage` and ask the condition" appeared five times with small copy-paste drift (one copy skipped the `isSuccess` guard). It uses `Result.map` — **not** `mapCatching` — precisely so condition exceptions escape (see above), while deserialization failures return `false` (a message that isn't a `T` simply doesn't match a `T`-assertion).

### Record flows: replay-then-live without a subscribe race

`peek*` semantics are "examine each observed record once, stop at the first satisfying one". A plain `SharedFlow` of records cannot implement this: a subscriber only sees emissions after subscription, so records observed before the `peek` call would be invisible — and `SharedFlow(replay = N)` would need an unbounded N to be correct. Instead:

```kotlin
private fun <T> replayThenLive(query: () -> Collection<T>, id: (T) -> String): Flow<T> = flow {
  val seen = HashSet<String>()
  version.collect { query().forEach { record -> if (seen.add(id(record))) emit(record) } }
}
```

Collecting `version` (a `StateFlow`) fires immediately, so the first pass re-queries the store and **replays** everything already recorded; each subsequent signal re-queries and emits only what the `seen` set hasn't delivered. The per-collector `HashSet` gives exactly-once delivery per flow instance without any shared subscription state. Cost is one id-set per active peek — bounded by records in a test run, alive only while collecting.

This replaced offset-based dedup (`it.offset > lastSeen`) for a correctness reason, not just style: the store's caches are hash maps with **no iteration order guarantee**, so "track the max offset seen" could skip a lower-offset record encountered *after* a higher-offset one in the same iteration. Dedup by id is order-independent.

These flows intentionally **never complete** — the store can always grow — so every consumer bounds them (`first`, `take`, `withTimeout`). `peek*` keeps `withTimeout(atLeastIn) { ... .first { condition(it) } }`, which also preserves the old timeout behavior (a `TimeoutCancellationException`) while gaining a useful return value: the matched record, which the loop version discarded.

The separate `events: SharedFlow<StoveMessageEvent>` (buffer 4096, `DROP_OLDEST`, `tryEmit`) survives as a *live-tail* surface for future diagnostics/DSL work. `tryEmit` + drop-oldest means store recording never adds suspension or blocks on a slow collector; the application interceptor already waits for its unary observer call, so ingestion should do only the minimum synchronous work. Nothing correctness-critical may subscribe to `events` precisely because it can drop; anything that must not miss data uses the store-backed flows.

### Test scoping: where the test id is captured, and why only once

`waitUntil*` captures `TraceContext.current()?.testId` **once, at assertion entry**, and threads it through as a value. `TraceContext` is an `InheritableThreadLocal` kept coherent across coroutine thread-hops by a `ThreadContextElement` — but the wait suspends and resumes many times, and betting that every resumption restores the thread-local (including inside `flow` machinery on other dispatchers) is fragile. Capturing at entry runs on the caller's thread before any suspension, where the context is guaranteed present; after that, scoping is just data.

The predicate itself (`belongsToTest`) is **fail-open** (decision above). Mechanically: extract a tag from `X-Stove-Test-Id` (case-insensitive; header keys survive Kafka verbatim but intermediate tooling may normalize case) or from the W3C `baggage` header (`stove.test.id` entry, percent-decoded — test names contain spaces, and OTel percent-encodes them; a literal `+` must survive decoding, hence the pre-escape before `URLDecoder`). No tag ⇒ match everything; different tag ⇒ exclude. Malformed baggage parses to "no tag", never to an exclusion, so a broken propagator can only reduce precision, not correctness.

Committed offsets are the one record type with no headers to tag, so scoped dumps mirror `snapshot()`: committed entries are included when their `(topic, partition)` pair appears among the *scoped consumed* messages — commits are only meaningful relative to consumption the test can see.

`throwIfFailed`/`throwIfRetried` needed the same scoping for a sharper reason than dump noise: they scan for *any* failed/retried message satisfying the user's condition, and conditions often match on fixture content that repeats across tests. Pre-scoping, test B could be failed by test A's DLT record whose payload happened to satisfy B's predicate — a real parallel-execution bug, not a cosmetic one.

### The ad-hoc `consumer()`: thread confinement as the design rule

`KafkaConsumer` is not thread-safe — unsynchronized concurrent access can throw `ConcurrentModificationException`. The old implementation broke this twice: the poll loop ran in one coroutine while `whileSelect`'s `onTimeout` called `consumer.close()` from another, and `commitSync()` ran on the channel-receiving side while `poll()` ran on the producing side. It mostly worked because the races were narrow; it was still wrong.

The rewrite makes serialization structural: everything happens in one `withContext(Dispatchers.IO)` coroutine — subscribe, poll, `onConsume`, commit, and (via `use { }`) close — so consumer calls cannot overlap. `Dispatchers.IO` does not promise OS-thread affinity; serialized access is the relevant guarantee. A monotonic deadline is checked before polling and before starting each callback. Once a callback starts, the duration deadline lets it and its exact-offset commit finish; external coroutine cancellation still propagates normally. A blocking `poll(pollTimeout)` is not interrupted mid-call, so normal duration shutdown can overshoot by up to `pollTimeout`, plus any callback that started before the deadline. The old inter-poll `delay(100)` was removed because `poll` already blocks up to its timeout.

### Records path: why the gRPC handlers became plain calls

The `record*` functions were `runBlocking { ... }` wrappers around code that never suspends — each call paid coroutine setup for nothing, on the gRPC ingestion path. They are now plain functions; `MutableStateFlow.update` and `MutableSharedFlow.tryEmit` are both non-suspending and thread-safe, so recording from gRPC handler threads needs no dispatcher at all.

The logging bug fixed alongside: `record.headers.firstNotNullOf { it.key == "testCase" }` returns the first *non-null transform result* — the lambda returns `Boolean`, which is never null, so this returned whether the *first arbitrary header* was named `testCase`, and **threw `NoSuchElementException` on header-less messages**. Thrown inside the gRPC handler after the store write, it surfaced as the `UNKNOWN`-status errors the bridge deliberately swallows — messages were recorded, but every header-less record produced a hidden error round-trip. `record.headers["testCase"]` is what it meant.

### What deliberately did not change

- **User-facing assertion/configuration API**: existing `shouldBe*`/`peek*` APIs and configuration constructors remain available; the `publish` JVM signature is unchanged and only its default-partition behavior is corrected. The generated bridge wire API is preserved because it is also consumed by the independently published Go module.
- **The caches**: Caffeine maps remain the storage; flows and signals sit beside them, not instead of them. Replacing storage was not needed for any goal above and would have rippled into the spring starter.
- **No versioned observer compatibility layer**: there is one service and it is the established unary contract. No V1/V2 pair, capability negotiation, semantic adapter, or "legacy" label is needed because the existing JVM/Go wire format remains current.
- **`stoveSerdeRef` default**: configured clients resolve serde by their per-system bridge id, so keyed systems no longer depend on one mutable serde. The global remains because it is also the public default used by `KafkaSystemOptions` and the standalone Stove Kafka serializers.
