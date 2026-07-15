# stove-wiremock & stove-grpc-mock Capability Roadmap

Working document for improving `lib/stove-wiremock` and `lib/stove-grpc-mock`, plus the dashboard surface they feed. Companion to `lib/stove-kafka/ROADMAP.md`, whose principles (test-scoped fail-open evidence, positive-evidence assertions, ambiguous signals to the dashboard) this roadmap adopts and extends.

## Goals

Make the mock systems the strongest external-dependency testing surface on the JVM, and turn the dashboard into **DevTools for the application's egress** — the Network tab backend developers never had. Three pillars, in order:

1. **Foundation & correctness** — test-scoped, fail-open call journals shared across both modules; fix the parallel-execution bugs.
2. **Verification & fidelity** — a typed gRPC verification API, and first-class failure-mode simulation (faults, latency, deadlines, retry journeys).
3. **Diagnostics & differentiators** — near-miss diffs, the exchange inspector, error-path coverage, and contracts emitted as a byproduct of passing tests.

## Priority order

**D1 (grpc-mock test scoping — live bug) ✅ → D2 (wiremock fail-open journal — decision violation) ✅ → D3 (shared journal foundation in core) → C1 (near-miss diagnostics) + C2 (exchange inspector) → A (grpc-mock verification + descriptor typing) → B (fidelity) → C3 (error-path coverage) → E (contracts, drift, live mode — later bets).**

Verification status for D1/D2: wiremock suite 62 tests and grpc-mock suite 25 tests green (including new fail-open scoping specs at both journal and server level), core suite green, `apiCheck` and `spotlessCheck` pass after API regeneration. Release note needed: `ReceivedRequest` gained a `testId` field (data-class constructor/copy binary change), and stubs registered outside a test context no longer carry a `"default"` test-id tag.

The mock-facing dashboard work rides the same reporter → sqlite → dashboard (stove-cli) → MCP pipeline as the Kafka diagnostics theme; panels and MCP queries ship together.

---

## Verified findings (2026-07-15)

Concrete defects found by source review, driving Theme D:

| # | Severity | Finding |
|---|---|---|
| M1 | Critical ✅ resolved | **grpc-mock `validate()` is not test-scoped.** `GrpcMockSystem.validate()` scans the process-global `requestLog` (Caffeine, max 10k, never cleared per test) for unmatched requests — test B fails validation because of test A's unmatched request. Same class of parallel-execution bug the Kafka work fixed in `throwIfFailed`. |
| M2 | Major ✅ resolved | **wiremock journal is fail-closed.** `WireMockCallJournal.record` drops serve events with no test id (`?: return`), and `validate()` counts only unmatched requests whose `X-Stove-Test-Id` header equals the current test. Apps that don't propagate the header get false `shouldHaveBeenCalled` failures (count 0) and false validation passes (untagged unmatched requests invisible to every test). Contradicts the Kafka fail-open scoping decision. |
| M3 | Major ✅ resolved | **grpc-mock handler type is registration-order-dependent.** `DynamicHandlerRegistry.lookupMethod` uses `stubs[methodName]?.firstOrNull()` to pick the gRPC method *type*; mixing stub kinds (e.g. `Error` + `ServerStream`) for one method gives whichever registered first control. Matching precedence is also undefined (first match wins). |
| M4 | Minor ✅ resolved | **Orphan coroutine scopes in bidi handling.** Two fresh `CoroutineScope(Dispatchers.IO)` per bidi call, never tied to the system lifecycle — the per-system-scope lesson from Kafka D2. Bidi also silently ignores its `requestMatcher` (KDoc admits it). |
| M5 | Minor ✅ resolved | **No per-test cleanup in grpc-mock.** No report-listener/journal-clearing equivalent of wiremock's `reportListener`; stubs and requests accumulate for the suite lifetime. |
| M6 | Minor | **Cross-test stub matches are invisible.** In both modules, a stub registered by test A remains matchable by test B (unless `removeStubAfterRequestMatched`); nothing surfaces when it happens, though the journals hold both test ids. |

---

## Theme D — Foundation and journal correctness

| Item | Status | Notes |
|---|---|---|
| grpc-mock test-scoped journal | ✅ | Fixes M1/M5. `GrpcCallJournal` replaced the global Caffeine `requestLog`: stubs are tagged with the registering test id (`RegisteredStub`), requests are attributed request-metadata-first (`x-stove-test-id`/baggage via the shared core extraction) with the matched stub's tag as fallback, `validate()` and `snapshot()` are scoped fail-open, and a report listener clears completed tests' journals. Stove's gRPC client already propagated `X-Stove-Test-Id` metadata, so no client change was needed. |
| wiremock fail-open journal | ✅ | Fixes M2. Untagged stubs and serve events land in shared buckets visible to every test instead of being dropped; attribution is request-header/baggage-first with stub metadata as fallback; `validate()` now reads the journal (not the raw server log) and counts untagged unmatched requests for every test; stubs registered outside test context stay untagged via `currentTestIdOrNull` (new core API) instead of being tagged `"default"`. |
| Shared journal foundation in core | ✅ | `com.trendyol.stove.scoping` in core now owns the generic fail-open `TestScopedJournal<T>`, the `TestScopeCleanupListener` (drains completed tests, clears retried test ids, never touches untagged), and the test-id header/baggage extraction (moved from `messaging.kafka`, which keeps thin forwarding functions for compatibility). Both mocks compose these; grpc-mock's bespoke journal class was deleted outright. **Explicitly excludes the Kafka StateFlow/`awaitRecords` wait engine** — mock verification is point-in-time (see decisions). |
| grpc-mock lifecycle scope | ✅ | Fixes M4. Bidi handlers and delayed dispatch run in one system-owned supervisor scope cancelled at close; bidi stubs reject request matchers explicitly instead of silently ignoring them. |
| Defined stub precedence | ✅ | Last-registered wins (test-local stubs override fixture defaults; with `removeStubAfterRequestMatched` the earlier stub serves again once the winner is consumed). Mixed method types for one method fail fast at registration — except `Error` stubs, which are type-agnostic and adapt to the method's real type. |
| Cross-test match warnings | ⬜ | Addresses M6 as a dashboard warning, never a failure: "request from test B was served by a stub registered in test A". |

## Theme A — Verification & typed API (grpc-mock's biggest functional hole)

| Item | Status | Notes |
|---|---|---|
| Typed gRPC verification | ✅ | `shouldHaveBeenCalled<T>(service, method, times) { condition }` / `shouldNotHaveBeenCalled<T>` — point-in-time, exact-count, test-scoped, counting what the application sent (matched or not). Failures report matching-vs-received counts plus parsed payloads. Descriptor overloads included. |
| Descriptor-typed stubbing | ✅ | All `mock*` and both typed verifications accept generated `MethodDescriptor`s; string overloads remain. |
| Reified request matchers | ✅ (bidi typed handler deferred) | `RequestMatcher.message<T> { predicate }` parses request bytes via the generated parser; unparseable bytes never match. The typed bidi handler remains raw `ByteArray` for now — revisit with descriptor-based marshalling. |
| `shouldNotHaveBeenCalled` kept as-is | ✅ decision | Sound for mocks, unlike Kafka negative assertions — see decisions log. |

## Theme B — Fidelity: simulating how real dependencies fail

| Item | Status | Notes |
|---|---|---|
| First-class HTTP faults & latency | ✅ | `mockFault(method, url, Fault.CONNECTION_RESET_BY_PEER)` plus a `delay: Duration?` parameter on every `mock*`/`mock*Containing` — thin wrappers over WireMock's native fault/fixed-delay support. "Client timeout + retry + circuit breaker" tests are one-liners. |
| Retry-journey DSL | ✅ | `behaviourFor(url, ::post) { failsTimes(2, withStatus = 503); thenSucceeds { response } }` — the HTTP twin of Kafka's planned retry-flow DSL. Feeds the retry-journey dashboard view later. |
| Dynamic lambda responses | ✅ | `mockDynamic(method, url) { request, serde -> aResponse()... }` — responses computed from the received request at serve time via a Stove `ResponseDefinitionTransformerV2`, correlated to stubs through metadata so it survives WireMock's stub-id assignment. |
| gRPC per-stub delay | ✅ | `delay: Duration?` on unary/stream/client-stream/error stubs, dispatched through the system-owned handler scope. `mockUnary(…, delay = 500.milliseconds)` against a client deadline yields `DEADLINE_EXCEEDED`. |
| Stream-then-error | ✅ | `mockServerStream(…, thenFailWith = Status.UNAVAILABLE)` emits all items and then fails instead of completing. |
| Error trailers & rich status | ✅ | `mockError(…, trailers = Metadata(…))` sends error trailers (the carrier for `google.rpc.Status` details) alongside status and description. |
| Built-in health & reflection services | ✅ | `GrpcMockSystemOptions(enableHealthService = true, enableReflectionService = true)` serve `grpc.health.v1.Health` and the reflection service via `io.grpc:grpc-services`; opt-in, off by default. |

## Theme C — Diagnostics & dashboard: DevTools for egress

| Item | Status | Notes |
|---|---|---|
| Near-miss reporting | ✅ | wiremock: `validate()` failures diff each unmatched request against the test-scoped closest stubs (WireMock's `Diff` renderer, distance-ranked, top 3; an exact-distance match is explained as consumed/late-registered), and zero-match verifications diff the pattern against the test's received requests. grpc-mock: rejection reasons are captured **at request time** (surviving stub removal) per candidate stub — which matcher rejected, expected-vs-received proto payloads for `ExactMessage` via the expected message's own parser. All reasons flow into the `AssertionError` and `ReportEntry.failure`. Dashboard/MCP rendering of the same data remains with C2. |
| Exchange inspector ("Network tab") | ⬜ | Per-test panel of every outbound exchange: method/URL or gRPC method, status, latency, matched-stub link, expandable request/response bodies (proto rendered as JSON via descriptors). Streams get a sub-timeline: each message a tick, so "4 items then INTERNAL" reads at a glance. Foundation for every panel below; mostly rendering over journals that already exist. |
| Unused-stub warnings | ⬜ | Stubs registered but never matched — dead fixture weight, or a test passing without the interaction it thinks it proves. Per-test warnings plus suite-wide aggregation (fixtures no test has needed for weeks). Dashboard warnings, never failures. |
| Error-path coverage matrix | ⬜ | The mocks know which failure modes of each dependency the suite has ever exercised. Aggregate per endpoint across the suite: dependency × endpoint × {2xx, 4xx, 5xx, fault, latency}. "payment-api `/charge`: 14 tests, all stubbed 200, zero failure-path tests" is a resilience blind-spot report generated from existing journal data — and it creates the demand the Theme B fidelity items satisfy. |
| Retry-journey & scenario view | ⬜ | `behaviourFor` state machines rendered with each request pinned to the state it hit (`503 → 503 → 200`), inter-attempt gaps visible (backoff made literal). Correlates with Kafka retry lanes on the shared choreography timeline. |
| Fault-experiment & deadline views | ⬜ | Injected latency/fault as a shaded region on the lane with the app's reaction readable (retries, or the *absence* of calls when a circuit breaker opened). gRPC: client deadline vs stub delay drawn together. |
| Choreography-lane integration | ⬜ | Mock exchanges join the planned per-test swimlane (trigger → Kafka publish/consume → HTTP/gRPC call), same sqlite store, same test-id keying as the Kafka diagnostics theme. |
| MCP query surface | ⬜ | Every panel doubles as an agent query: "show the exact request test X sent to /payments", "which stubs went unused this run", "which tests exercise payment-api failure paths", "what changed in outbound traffic since the last green run". |

## Theme E — Differentiators (later bets)

- **Contracts as a byproduct**: every stub matched in a passing test is a consumer contract — real request the app sent, response it was verified to handle. `stove contracts export` emits Pact files / OpenAPI fragments per dependency straight from the journals; CI diffs them across commits. Consumer-driven contract testing with zero additional test-writing.
- **Contract-drift panel**: the dashboard's cross-run sqlite tracks request shapes per dependency and diffs run-over-run — "commit abc: new field `currency` in POST /charge; `X-Idempotency-Key` no longer sent". Valuable before any Pact export exists.
- **Contract-drift guard at registration**: optional OpenAPI/proto add-on validating stub responses against the dependency's real schema when the stub is registered — attacks "your mock lies about the real API" at the source.
- **Record & replay proxy mode**: point wiremock at the real API once (`proxyAndRecord`), capture test-id-scoped stubs as fixtures, replay forever. WireMock supports the mechanics natively.
- **Live control panel**: in a `stove up` dev session the mock lanes go live — watch outbound requests arrive, click an unmatched request → "create stub from this request" (pre-filled from the near-miss), edit a response and replay; interactive work exports as test code.

---

## Decisions log

**Mock verifications are point-in-time — no `within`/timeout parameter, no signal-driven waits.** There is no async or background process between the app and a mock: by the time the test asserts on mocks, the call either happened or it didn't. Any time gap in the flow is absorbed by the anchor assertion that precedes it (Kafka `atLeastIn`, awaited HTTP responses), and commit-aware consumption means a passing `waitUntilConsumed` implies the handler — and its outbound mock calls — completed. A time parameter would duplicate the wait the anchor already performed and invite papering over ordering mistakes with timeouts. Consequently the shared journal foundation excludes the Kafka StateFlow/`awaitRecords` wait engine.

**`shouldNotHaveBeenCalled` is sound for mocks.** The Kafka roadmap rejected plain negative assertions because absence there is ambiguous — a dead bridge or broken interceptor is indistinguishable from correct suppression. That ambiguity doesn't exist for mocks: the mock server *is* the endpoint, not a separate observer that can silently break, and a misconfigured base URL fails the positive-path assertions first. No anchored-absence construct needed.

**Test scoping is fail-open**, matching the Kafka decision: untagged evidence matches every test; only evidence provably tagged with a different test id is excluded. A broken or absent propagator may reduce precision, never correctness. The current wiremock journal violates this (finding M2) and is corrected in Theme D.

**Ambiguous signals go to the dashboard, never assertions** (inherited from Kafka): unused stubs, cross-test stub matches, and drift observations are warnings and reports, not test failures. Assertions stay reserved for positive evidence.

**Stub precedence: last-registered wins** (proposed, confirm before D). Test-local stubs override fixture defaults, matching WireMock's own semantics; conflicting gRPC method types for one method fail fast at registration.
