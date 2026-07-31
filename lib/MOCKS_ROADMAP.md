# stove-wiremock & stove-grpc-mock Capability Roadmap

Working document for improving `lib/stove-wiremock` and `lib/stove-grpc-mock`, plus the dashboard surface they feed. Companion to `lib/stove-kafka/ROADMAP.md`, whose principles (test-scoped fail-open evidence, positive-evidence assertions, ambiguous signals to the dashboard) this roadmap adopts and extends.

## Goals

Make the mock systems the strongest external-dependency testing surface on the JVM, and turn the dashboard into **DevTools for the application's egress** — the Network tab backend developers never had. Three pillars, in order:

1. **Foundation & correctness** — test-scoped, fail-open call journals shared across both modules; fix the parallel-execution bugs.
2. **Verification & fidelity** — a typed gRPC verification API, and first-class failure-mode simulation (faults, latency, deadlines, retry journeys).
3. **Diagnostics & differentiators** — near-miss diffs, the exchange inspector, error-path coverage, and contracts emitted as a byproduct of passing tests.

## Priority order

**D1 (grpc-mock test scoping — live bug) ✅ → D2 (wiremock fail-open journal — decision violation) ✅ → D3 (shared journal foundation in core) ✅ → C1 (near-miss diagnostics) ✅ → A (grpc-mock verification + descriptor typing) ✅ → B (fidelity, both modules) ✅ → C2 (exchange inspector) + C3 (error-path coverage) + cross-test match warnings → E (contracts, drift, live mode — later bets).**

The reporter → stove-cli ingestion/storage/API/SPA path is now complete for mock interactions, warnings, and failure-time snapshots. What remains is the derived C3 aggregation views, deeper choreography integration, optional MCP queries over the persisted data, and the Theme E later bets.

Verification status for the library work: core suite 323 tests, wiremock 68, grpc-mock 35 — all green, including dedicated specs for fail-open scoping, stub precedence/type-conflict semantics, near-miss diagnostics, typed verification, and both modules' fidelity features. `apiCheck` and `spotlessCheck` pass after API regeneration. Release notes needed: `ReceivedRequest` gained a `testId` field and `StubDefinition` subclasses gained `delay`/`thenFailWith`/`trailers` fields (data-class constructor/copy binary changes); `delay: Duration?` parameters were appended to wiremock `mock*` signatures (binary change, source-compatible); stubs registered outside a test context no longer carry a `"default"` test-id tag; grpc-mock stub matching is now last-registered-wins and mixed method types per method fail fast.

The mock-facing dashboard work rides the same reporter → sqlite → dashboard (stove-cli) → MCP pipeline as the Kafka diagnostics theme. Reporter events, sqlite persistence, live SSE payloads, per-test/per-run HTTP reads, and the SPA evidence workbench are complete; MCP exposure can follow independently.

---

## Verified findings (2026-07-15)

Concrete defects found by source review, driving Theme D:

| # | Severity | Finding |
|---|---|---|
| M1 | Critical ✅ resolved | **grpc-mock `validate()` is not test-scoped.** `GrpcMockSystem.validate()` scans the process-global `requestLog` (Caffeine, max 10k, never cleared per test) for unmatched requests — test B fails validation because of test A's unmatched request. Same class of parallel-execution bug the Kafka work fixed in `throwIfFailed`. |
| M2 | Major ✅ resolved | **wiremock journal is fail-closed.** `WireMockCallJournal.record` drops serve events with no test id (`?: return`), and `validate()` counts only unmatched requests whose `X-Stove-Test-Id` header equals the current test. Apps that don't propagate the header get false `shouldHaveBeenCalled` failures (count 0) and false validation passes (untagged unmatched requests invisible to every test). Contradicts the Kafka fail-open scoping decision. |
| M3 | Major ✅ resolved | **grpc-mock handler type is registration-order-dependent.** `DynamicHandlerRegistry.lookupMethod` uses `stubs[methodName]?.firstOrNull()` to pick the gRPC method *type*; mixing stub kinds (e.g. `Error` + `ServerStream`) for one method gives whichever registered first control. Matching precedence is also undefined (first match wins). |
| M4 | Minor ✅ resolved | **Orphan coroutine scopes in bidi handling.** Two fresh `CoroutineScope(Dispatchers.IO)` per bidi call, never tied to the system lifecycle — the per-system-scope lesson from Kafka D2. Bidi also silently ignores its `requestMatcher` (KDoc admits it). |
| M5 | Minor ✅ resolved | **No per-test cleanup in grpc-mock.** No report-listener/journal-clearing equivalent of wiremock's `reportListener`; stubs and requests accumulated for the suite lifetime. Both request evidence and live test-owned stubs are now lifecycle-cleaned; untagged suite fixtures remain active. |
| M6 | Minor ✅ resolved | **Cross-test stub matches are invisible.** In both modules, a stub registered by test A remains matchable by test B (unless `removeStubAfterRequestMatched`); nothing surfaces when it happens, though the journals hold both test ids. |

---

## Theme D — Foundation and journal correctness

| Item | Status | Notes |
|---|---|---|
| grpc-mock test-scoped journal | ✅ | Fixes M1/M5. `GrpcCallJournal` replaced the global Caffeine `requestLog`: stubs are tagged with the registering test id (`RegisteredStub`), requests are attributed request-metadata-first (`x-stove-test-id`/baggage via the shared core extraction) with the matched stub's tag as fallback, `validate()` and `snapshot()` are scoped fail-open, and lifecycle listeners clear completed request evidence and remove completed tests' live stubs. Stove's gRPC client already propagated `X-Stove-Test-Id` metadata, so no client change was needed. |
| wiremock fail-open journal | ✅ | Fixes M2. Untagged suite stubs remain visible to every test, while untagged serve events are visible only to test lifecycle windows that overlap the request (concurrent tests intentionally share ambiguous evidence; future tests do not inherit old traffic). Attribution is request-header/baggage-first with active stub metadata as fallback; completed test-owned mappings are removed from the live server, and stubs registered outside test context stay untagged via `currentTestIdOrNull` instead of being tagged `"default"`. |
| Shared journal foundation in core | ✅ | `com.trendyol.stove.scoping` in core now owns the generic fail-open `TestScopedJournal<T>`, lifecycle windows for ambiguous untagged request evidence, the `TestScopeCleanupListener` (drains completed tests and clears retried test ids), and test-id header/baggage extraction. Both mocks compose these; grpc-mock's bespoke journal class was deleted outright. **Explicitly excludes the Kafka StateFlow/`awaitRecords` wait engine** — mock verification is point-in-time (see decisions). |
| grpc-mock lifecycle scope | ✅ | Fixes M4. Bidi handlers and delayed dispatch run in one system-owned supervisor scope cancelled at close; bidi stubs reject request matchers explicitly instead of silently ignoring them. |
| Defined stub precedence | ✅ | Last-registered wins (test-local stubs override fixture defaults; with `removeStubAfterRequestMatched` the earlier stub serves again once the winner is consumed). Mixed method types for one method fail fast at registration — except `Error` stubs, which are type-agnostic and adapt to the method's real type. |
| Unknown-method capture (grpc-mock) | ✅ | Calls to methods with **no stubs at all** used to short-circuit inside gRPC (`lookupMethod` returned null → `UNIMPLEMENTED` before any Stove code ran) and stayed invisible to the journal — a typo'd method name never appeared in `validate()` or diagnostics. The registry now serves a synthetic recorder handler: journals the request (method + metadata, fail-open attribution, "no stubs registered" near-miss) and answers `UNIMPLEMENTED` exactly as before. With this, every request that reaches either mock is captured — the completeness the exchange-inspector view (C2) assumes. |
| Cross-test match warnings | ✅ | Fixes M6 via the warnings pipeline: both mocks implement `MockWarningPublisher` and raise `CROSS_TEST_MATCH` at serve time when request and stub carry *provably different* test ids. Companion warnings are computed from tagged-only evidence: `UNUSED_STUB` tracks matches globally by stub id (including cross-test matches), and `UNVALIDATED_UNMATCHED` is emitted only when the owning test did not call `validate()`. Forwarded as `MockWarningEvent`; never failures. |

## Theme A — Verification & typed API (grpc-mock's biggest functional hole)

| Item | Status | Notes |
|---|---|---|
| Typed gRPC verification | ✅ | String overloads take an explicit generated `Parser<T>`; descriptor overloads infer the request type and decode with the method's configured marshaller. Both are point-in-time, exact-count, test-scoped, and count what the application sent (matched or not). Decode failures fail closed with payload diagnostics. |
| Descriptor-typed stubbing | ✅ | All `mock*` and both typed verifications accept generated `MethodDescriptor`s; string overloads remain. |
| Typed request matchers | ✅ (bidi typed handler deferred) | `RequestMatcher.message(Request.parser()) { predicate }` uses an explicit parser; `RequestMatcher.message(methodDescriptor) { predicate }` uses the descriptor marshaller and cannot drift from the RPC request type. Unparseable bytes never match and produce a specific near-miss. The typed bidi handler remains raw `ByteArray` for now. |
| `shouldNotHaveBeenCalled` kept as-is | ✅ decision | Sound for mocks, unlike Kafka negative assertions — see decisions log. |

## Theme B — Fidelity: simulating how real dependencies fail

| Item | Status | Notes |
|---|---|---|
| First-class HTTP faults & latency | ✅ | `mockFault(method, url, Fault.CONNECTION_RESET_BY_PEER)` plus a `delay: Duration?` parameter on every `mock*`/`mock*Containing` — thin wrappers over WireMock's native fault/fixed-delay support. "Client timeout + retry + circuit breaker" tests are one-liners. |
| Retry-journey DSL | ✅ | `behaviourFor(url, ::post) { failsTimes(2, withStatus = 503); thenSucceeds { response } }` — the HTTP twin of Kafka's planned retry-flow DSL. `thenSucceeds` is a stable terminal state, including when one-shot stub removal is enabled. Feeds the retry-journey dashboard view later. |
| Dynamic lambda responses | ✅ | `mockDynamic(method, url) { request, serde -> aResponse()... }` — responses computed from the received request at serve time via a Stove `ResponseDefinitionTransformerV2`, correlated to stubs through metadata so it survives WireMock's stub-id assignment. |
| gRPC per-stub delay | ✅ | `delay: Duration?` on unary/stream/client-stream/error stubs, dispatched through the system-owned handler scope. `mockUnary(…, delay = 500.milliseconds)` against a client deadline yields `DEADLINE_EXCEEDED`. |
| Stream-then-error | ✅ | `mockServerStream(…, thenFailWith = Status.UNAVAILABLE)` emits all items and then fails instead of completing. |
| Error trailers & rich status | ✅ | `mockError(…, trailers = Metadata(…))` sends error trailers (the carrier for `google.rpc.Status` details) alongside status and description. |
| Built-in health & reflection services | ✅ | `GrpcMockSystemOptions(enableHealthService = true, enableReflectionService = true)` serve `grpc.health.v1.Health` and the reflection service via `io.grpc:grpc-services`; opt-in, off by default. |

## Theme C — Diagnostics & dashboard: DevTools for egress

| Item | Status | Notes |
|---|---|---|
| Near-miss reporting | ✅ | wiremock: `validate()` failures diff each unmatched request against the test-scoped closest stubs (WireMock's `Diff` renderer, distance-ranked, top 3; an exact-distance match is explained as consumed/late-registered), and zero-match verifications diff the pattern against the test's received requests. grpc-mock: rejection reasons are captured **at request time** (surviving stub removal) per candidate stub — which matcher rejected, expected-vs-received proto payloads for `ExactMessage` via the expected message's own parser. All reasons flow into the `AssertionError` and `ReportEntry.failure`. Dashboard/MCP rendering of the same data remains with C2. |
| Exchange inspector ("Network tab") | ✅ | Both mocks emit one `MockInteraction` per completed exchange — matched or not — with final status, latency, truncated bodies (message/byte counts for gRPC), near-miss candidates, trace id from `traceparent`, and **proven-only attribution** (`PROVEN_HEADER`/`PROVEN_BAGGAGE`/`PROVEN_STUB`/`UNATTRIBUTED` — never inferred). Scenario state transitions, configured delay, injected fault/error provenance, and gRPC client deadline are carried end-to-end through proto, live SSE, sqlite and REST; live and persisted `near_misses` both serialize as arrays. The SPA renders these as an expandable exchange journal with filters, latency pulse, request/response bodies, near misses, and an explicit unattributed run-level "ambient" scope. |
| Unused-stub and mock warnings | 🚧 panels ✅, historical aggregation pending | `UNUSED_STUB`, `CROSS_TEST_MATCH`, and `UNVALIDATED_UNMATCHED` warnings are emitted as diagnostics, never failures. Stove-cli carries their optional test ids through unchanged, persists them, publishes live SSE payloads, exposes per-test/per-run HTTP reads, and renders them in the scoped diagnostic-signal lane. Remaining: suite-wide/cross-run aggregation such as "fixtures no test has needed for weeks". |
| Error-path coverage matrix | ⬜ | The mocks know which failure modes of each dependency the suite has ever exercised. Aggregate per endpoint across the suite: dependency × endpoint × {2xx, 4xx, 5xx, fault, latency}. "payment-api `/charge`: 14 tests, all stubbed 200, zero failure-path tests" is a resilience blind-spot report generated from existing journal data — and it creates the demand the Theme B fidelity items satisfy. |
| Retry-journey & scenario view | 🚧 core exchange view ✅, choreography overlay pending | `behaviourFor` state machines render each request with the scenario name and required → next state transition, making journeys such as `503 → 503 → 200` readable in order. `scenario_name`, required state and next state are persisted per exchange. Remaining: literal inter-attempt/backoff gaps and correlation with Kafka retry lanes on the shared choreography timeline. |
| Fault-experiment & deadline views | 🚧 core exchange view ✅, reaction overlay pending | The exchange journal renders configured delay, injected fault/error provenance, final status, observed latency, and the gRPC client deadline remaining at server arrival; deadline cancellations appear as `DEADLINE_EXCEEDED`. Remaining: shaded experiment regions and the application's downstream reaction/absence-of-calls overlay for retries and circuit breakers. |
| Choreography-lane integration | ⬜ | Mock exchanges join the planned per-test swimlane (trigger → Kafka publish/consume → HTTP/gRPC call), same sqlite store, same test-id keying as the Kafka diagnostics theme. |
| MCP query surface | ⬜ optional | The persisted data can optionally back agent queries such as "show the exact request test X sent to /payments", "which stubs went unused this run", "which tests exercise payment-api failure paths", and "what changed in outbound traffic since the last green run". |

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

**Test scoping is fail-open**, matching the Kafka decision: untagged request evidence is visible to every test active when it was observed; only evidence provably tagged with a different test id is excluded. Untagged suite fixtures remain visible to every test. A broken or absent propagator may reduce precision, never correctness, while lifecycle windows prevent old ambiguous traffic from being replayed into tests that started later.

**Ambiguous signals go to the dashboard, never assertions** (inherited from Kafka): unused stubs, cross-test stub matches, and drift observations are warnings and reports, not test failures. Assertions stay reserved for positive evidence.

**Stub precedence: last-registered wins.** Test-local stubs override fixture defaults, matching WireMock's own semantics; conflicting gRPC method types for one method fail fast at registration.

**Attribution is proven-only — no inference, dashboard included.** A mock exchange is attributed to a test only through provable evidence: the `X-Stove-Test-Id` header, W3C baggage, or the matched stub's registration tag (the fixture carries the identity, so matched traffic needs no propagation at all). Everything else is explicitly `UNATTRIBUTED` and rendered in a run-level lane — never guessed into a test by timestamps or any other heuristic. In tests we want certainty, not blur; the unattributed lane is itself actionable (fix the stub matcher, or enable stove-tracing for baggage propagation).
