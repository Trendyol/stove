# stove-kafka Capability Roadmap

Working document for improving `lib/stove-kafka`. Captures the initial ideas, the decisions that shaped them, and implementation progress.

## Goals

Make stove-kafka the strongest Kafka e2e-testing surface on the JVM by improving three things in order:

1. **Foundation** — event-driven internals and test-scoped isolation, so everything built on top is fast, parallel-safe, and debuggable.
2. **Diagnostics** — feed richer failure evidence into the existing reporter → dashboard (stove-cli) → MCP pipeline.
3. **Fidelity & assertions** — close publishing gaps and add assertion power, always based on positive evidence.

## Priority order

Agreed sequence: **D (foundation) → C (diagnostics) → B (publishing fidelity) → A (assertions, deferred — discuss before starting)**. E-theme differentiators (chaos, schema registry, record & replay) are later bets, picked by audience.

---

## Theme D — Foundation ✅ done

| Item | Status | Notes |
|---|---|---|
| Flow-based `MessageStore` | ✅ | `version: StateFlow<Long>` bumped on every record; public `events: SharedFlow<StoveMessageEvent>`; `awaitNewRecords(sinceVersion)` with capture-before-check so wake-ups cannot be missed. |
| Signal-driven waits | ✅ | `waitUntilConditionMet` / `waitUntilCount` and the three `peek*` loops no longer poll with `delay(50/100)`; they suspend on the store's version signal. |
| Test-scoped assertions | ✅ | `waitUntilConsumed/Published/Failed/Retried` and `throwIfFailed/throwIfRetried` filter by the current test id (from `TraceContext`), fail-open (see decisions). Fixed a latent bug: another test's failed message with matching content could fail the current test. |
| Test-scoped failure dumps | ✅ | Timeout `AssertionError`s embed `MessageStore.dump(testId)` — only the current test's messages plus a `N message(s) from other tests hidden` count — instead of everything observed since the suite started. |
| Bonus fixes | ✅ | `firstNotNullOf { it.key == "testCase" }` logging crash on header-less messages (source of silently swallowed gRPC UNKNOWN errors in the bridge); removed pointless `runBlocking` wrappers in record paths. |
| Flow-idiom refactor | ✅ | Replay-then-live record flows on `MessageStore` (`consumedRecords()` etc.); all waits collapsed into one `awaitRecords` helper built on `version.first { }` — no `while(true)` loops, no manual version bookkeeping; `peek*` return the matched record; shared `matches()` helper removed the deserialize+condition boilerplate; `shouldBeRetried` now reports like the other assertions. |
| Ad-hoc consumer thread-safety fix | ✅ | The old `consumer()` closed and committed the `KafkaConsumer` from different coroutines than the poll loop (KafkaConsumer is not thread-safe) and polled with a redundant `delay(100)`. Rewritten single-threaded on `Dispatchers.IO` with `use { }` + `withTimeoutOrNull`. |

Verified: 44/44 tests in all three runtime modes (container, embedded, provided), `apiCheck` and `detekt` clean. New coverage in `StoreEventsAndScopingTests`.

**Follow-up:** `starters/spring/stove-spring-kafka` has its own separate `MessageStore` (still polling, still global dumps) and needs the same treatment.

## Theme C — Diagnostics & observability ⏳ next

| Item | Status | Notes |
|---|---|---|
| Near-miss diagnostics | ⬜ | On assertion timeout, attach same-type candidates that failed the condition (field-level diff) to `ReportEntry.failure`. Console shows closest miss; dashboard shows all; MCP gives agents "3 candidates, closest differed in `amount`" instead of "timeout". Best value-per-effort item. |
| Message choreography view | ⬜ | stove-cli dashboard swimlane per test: trigger → publish → consume → retry → DLT → commit, correlated with OTel spans (same sqlite store, same test-id keying). Mermaid export nearly free. |
| Message-leak warnings | ⬜ | Events published that nothing consumed, or matches arriving after the assertion window — surfaced as dashboard timeline *warnings*, never test failures (see decisions). |
| Consumer-group lag panel | ⬜ | Live `Admin`-fed panel: group state, per-partition lag, rebalance events on the timeline. Assertion API can follow once the data proves useful. |
| Cross-run trend analytics | ⬜ | `~/.stove-dashboard.db` persists across sessions: track assertion latency vs. timeout per test per run; flag assertions trending toward their timeout (flakiness early warning). |

## Theme B — Publishing fidelity ⬜

| Item | Status | Notes |
|---|---|---|
| Fix `partition: Int = 0` default | ⬜ | Every `publish` currently lands on partition 0, silently bypassing key-based partitioning. Should be absent-by-default so the partitioner decides. Arguably a bug fix; prerequisite for honest per-key ordering tests. |
| `publishTombstone(topic, key)` | ⬜ | Null-value publishing for compacted-topic deletion logic — currently untestable. |
| `publishRaw(topic, bytes)` | ⬜ | Poison-pill/malformed payloads to make "deserialization failure lands in DLT" a one-liner. |

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

## Decisions log

**No plain negative assertions.** `shouldNotBeConsumed<T>` over a time window was rejected: absence is ambiguous — correct suppression by the app is indistinguishable from a broken interceptor, misconfigured client, or dead bridge. Validating arrival is more valuable than asserting non-arrival.

**Ambiguous signals go to the dashboard, not assertions.** Evidence that is suggestive but not conclusive (message leaks, late matches) is surfaced as dashboard warnings, never as test failures. Assertions stay reserved for positive evidence.

**Absence is only assertable anchored to proven liveness.** The acceptable form of "nothing happened" requires a liveness anchor first: a consumed-and-committed trigger, or a completed trace (stove-tracing's `shouldNotContainSpan` is sound precisely because the span tree proves the pipeline ran). The planned construct makes the anchor mandatory so the unsound half cannot be written alone, and failures distinguish "pipeline never proved alive" from "message absent".

**Test scoping is fail-open.** Applications may publish with no propagation at all (no OTel agent, no manual header copying), so untagged messages always match every test — behavior identical to before scoping existed. A message is excluded only when *provably* tagged with a different test id. Tags are only ever written by Stove, via two transports: the `X-Stove-Test-Id` Kafka header (injected by Stove's `publish`, carried into the app's consumed records by Kafka itself, no OTel needed) and the W3C `baggage` header (`stove.test.id`, present on app-published messages only when the OTel agent propagates context). Extraction is liberal: case-insensitive header keys, percent-decoded baggage values, malformed baggage degrades to "untagged" rather than excluding.

**StateFlow is the wait primitive.** `MessageStore.version` is a `StateFlow<Long>` bumped on every record, so `version.first { condition() }` evaluates the condition immediately and then once per stored record — signal-driven waiting with no missed wake-ups, no polling, and no manual version capture at call sites. Record streams (`consumedRecords()` etc.) are built the same way: collect `version`, re-query the store, dedup by id — replay-then-live with no subscribe race.

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

evaluates the condition immediately at subscription (covering records that already arrived) and then exactly once per stored record. There is no gap in which a record can slip by, no version bookkeeping at the call site, and no loop — `first` *is* the loop.

Two properties make this sound here:

- **Conditions are monotone.** The store is append-only for the duration of a wait (caches are only cleaned at system close), so once a condition over store contents becomes true it stays true. `StateFlow` conflates rapid updates — under a burst, a collector may observe version 5 and then 9, skipping 6–8 — but conflation can only *batch* re-checks, never skip a satisfying state, because the condition re-queries the live store rather than inspecting the version number.
- **The store is the source of truth, not the signal.** The condition closure re-reads the caches on every evaluation. Even if every intermediate signal were dropped, one evaluation against the final state gives the right answer.

`version` is a monotone counter rather than, say, a `StateFlow<List<Message>>` because state-carrying flows would need a copy of the collections per update (or expose mutable state to collectors), and `StateFlow` equality-conflates — two different stores states could compare equal with careless value semantics. A counter is allocation-free, never equal to its predecessor, and pushes the querying where it belongs: the caches.

### `awaitRecords`: one helper instead of four wait shapes

`waitUntilConsumed/Published/Failed/Retried` differed only in *which* collection they query and *how many* matches suffice. That variance is exactly two parameters, so the machinery collapsed into:

```kotlin
suspend fun <T> awaitRecords(within, subject, testId, count = 1, query, predicate): Collection<T>
```

Design choices inside it:

- **`withTimeoutOrNull` instead of `runCatching` + `withTimeout`.** The old code caught *every* exception and reported it as `GOT A TIMEOUT`, so an NPE inside a user's condition lambda was indistinguishable from a slow consumer — a debugging trap. `withTimeoutOrNull` encodes "timeout" as `null` in the type; anything thrown by the predicate propagates unchanged. The error path is reached only by genuinely running out of time, and its message can therefore honestly report expected vs. found counts plus the test-scoped dump.
- **`count` folded in.** The old `waitUntilRetried` nested one wait inside another (`waitUntilConditionMet` inside `waitUntilCount`), each with its own `atLeastIn` — worst-case 2× the caller's timeout, and two copies of timeout error handling. `first { matching().size >= count }` is the same statement with `count = 1` as a degenerate case.
- **The predicate re-runs against the whole collection on each signal.** This is deliberate O(n·signals) simplicity: n is bounded by messages observed in a test run, conditions are cheap (deserialize + field checks, with deserialization failures cached as `false` by short-circuit), and the alternative — incremental evaluation over only-new records — needs the dedup machinery that record flows provide, without being on the assertion hot path.

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

The separate `events: SharedFlow<StoveMessageEvent>` (buffer 4096, `DROP_OLDEST`, `tryEmit`) survives as a *live-tail* surface for future diagnostics/DSL work. `tryEmit` + drop-oldest means recording never suspends and never blocks on a slow collector — the gRPC ingestion path (`onConsumedMessage` etc.) must stay non-blocking because the application's interceptor calls are on its consume/produce path. Nothing correctness-critical may subscribe to `events` precisely because it can drop; anything that must not miss data uses the store-backed flows.

### Test scoping: where the test id is captured, and why only once

`waitUntil*` captures `TraceContext.current()?.testId` **once, at assertion entry**, and threads it through as a value. `TraceContext` is an `InheritableThreadLocal` kept coherent across coroutine thread-hops by a `ThreadContextElement` — but the wait suspends and resumes many times, and betting that every resumption restores the thread-local (including inside `flow` machinery on other dispatchers) is fragile. Capturing at entry runs on the caller's thread before any suspension, where the context is guaranteed present; after that, scoping is just data.

The predicate itself (`belongsToTest`) is **fail-open** (decision above). Mechanically: extract a tag from `X-Stove-Test-Id` (case-insensitive; header keys survive Kafka verbatim but intermediate tooling may normalize case) or from the W3C `baggage` header (`stove.test.id` entry, percent-decoded — test names contain spaces, and OTel percent-encodes them; a literal `+` must survive decoding, hence the pre-escape before `URLDecoder`). No tag ⇒ match everything; different tag ⇒ exclude. Malformed baggage parses to "no tag", never to an exclusion, so a broken propagator can only reduce precision, not correctness.

Committed offsets are the one record type with no headers to tag, so scoped dumps mirror `snapshot()`: committed entries are included when their `(topic, partition)` pair appears among the *scoped consumed* messages — commits are only meaningful relative to consumption the test can see.

`throwIfFailed`/`throwIfRetried` needed the same scoping for a sharper reason than dump noise: they scan for *any* failed/retried message satisfying the user's condition, and conditions often match on fixture content that repeats across tests. Pre-scoping, test B could be failed by test A's DLT record whose payload happened to satisfy B's predicate — a real parallel-execution bug, not a cosmetic one.

### The ad-hoc `consumer()`: thread confinement as the design rule

`KafkaConsumer` is single-threaded by contract — it throws `ConcurrentModificationException` on cross-thread access. The old implementation broke this twice: the poll loop ran in one coroutine while `whileSelect`'s `onTimeout` called `consumer.close()` from another, and `commitSync()` ran on the channel-receiving side while `poll()` ran on the producing side. It mostly worked because the races were narrow; it was still wrong.

The rewrite makes the confinement structural: everything happens in one `withContext(Dispatchers.IO)` block — subscribe, poll, `onConsume`, commit, and (via `use { }`) close — so no cross-thread call *can* exist. `withTimeoutOrNull(keepConsumingAtLeastFor)` expresses that elapsing the duration is normal termination (null), not an error. Cancellation is observed between polls: a blocking `poll(pollTimeout)` isn't interrupted mid-call, so shutdown can overshoot by at most `pollTimeout` — the same bound the old code had, with its inter-poll `delay(100)` removed because `poll` already blocks up to the poll timeout; the delay only added dead time.

### Records path: why the gRPC handlers became plain calls

The `record*` functions were `runBlocking { ... }` wrappers around code that never suspends — each call paid coroutine setup for nothing, on the gRPC ingestion path. They are now plain functions; `MutableStateFlow.update` and `MutableSharedFlow.tryEmit` are both non-suspending and thread-safe, so recording from gRPC handler threads needs no dispatcher at all.

The logging bug fixed alongside: `record.headers.firstNotNullOf { it.key == "testCase" }` returns the first *non-null transform result* — the lambda returns `Boolean`, which is never null, so this returned whether the *first arbitrary header* was named `testCase`, and **threw `NoSuchElementException` on header-less messages**. Thrown inside the gRPC handler after the store write, it surfaced as the `UNKNOWN`-status errors the bridge deliberately swallows — messages were recorded, but every header-less record produced a hidden error round-trip. `record.headers["testCase"]` is what it meant.

### What deliberately did not change

- **Public API shape**: all `shouldBe*`/`peek*` signatures are additive or return-type-improved (`Unit` → matched record; binary-visible but source-compatible for callers that ignore the result). `apiCheck` gates the rest.
- **The caches**: Caffeine maps remain the storage; flows and signals sit beside them, not instead of them. Replacing storage was not needed for any goal above and would have rippled into the spring starter.
- **`stoveSerdeRef` global**: a known smell (mutable global connecting `KafkaSystem` to interceptor instances the Kafka client constructs reflectively), but it is the bridge's process-wide rendezvous point; redesigning it belongs with a dedicated bridge-configuration story, not this refactor.
