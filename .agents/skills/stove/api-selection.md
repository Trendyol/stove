# Choosing Stove APIs

Read this before introducing a native handle or an SDK-based test helper. Stove operations can add reporting, serialization, test correlation, mock ownership, and cleanup beyond the SDK call. Reimplementing the same operation can lose those behaviors.

## Find the supported operation

1. Express the required behavior: for example, assert a response header, match a JSON field, capture a request, or execute a bound statement. Search by behavior and related terms, not just a guessed method name.
2. Check [writing-tests.md](writing-tests.md) and the resolved version's API. Inspect the relevant system class, overloads, extension functions (including companion extensions), base classes, and request/response builders. Examples are not an exhaustive API catalog.
3. If source is absent from the application checkout, inspect [local source artifacts](gradle-config.md#resolve-api-ambiguity-from-local-artifacts) or the matching upstream release. Missing local source does not establish a missing API; state any unresolved limitation accurately.
4. Compare semantics as well as names, then use the existing DSL when it covers the behavior. Otherwise check system options and supported callbacks, then the narrowest managed native extension before taking an unrestricted handle. Do not recreate serialization, assertions, retry scenarios, verification, or request inspection that the system already supplies.

## Common replacements

These are search starting points; confirm signatures in the project's resolved version.

| Required behavior | Inspect before reaching for a native handle |
|---|---|
| HTTP status, body, headers, or error responses | `getResponse`, typed verb helpers, and bodiless response helpers |
| HTTP authentication, headers, query parameters, or client setup | Helper arguments and `HttpClientSystemOptions`, including `configureClient` |
| HTTP NDJSON or WebSockets | `readJsonStream<T>`; `webSocket` and its session helpers |
| WireMock matching, responses, delays, or retry/recovery sequences | `request` / `respond`, verb helpers, `stub`, and `behaviour` |
| WireMock verification and captured requests | `shouldHaveBeenCalled`, `shouldNotHaveBeenCalled`, `callsFor`, reusable `RequestSpec`s, and native request-pattern overloads |
| WireMock dynamic responses or network faults | `mockDynamic` and `mockFault` before constructing raw mappings |
| SQL execution or typed queries | `shouldExecute` and `shouldQuery`, including inherited methods and mapper/parameter overloads; MSSQL also has an `ops {}` callback |
| Cassandra execution or queries | `shouldExecute` and `shouldQuery`, including their `BoundStatement` overloads |
| MongoDB, Elasticsearch, or Couchbase document operations | `save` / `saveToDefaultCollection`, `shouldGet`, `shouldQuery`, `shouldDelete`, and `shouldNotExist` as supported by that system |
| Kafka publishing or observations | `publish`, typed assertions, `peek*`, and standalone `consumer`; preserve their distinct observation semantics described in [writing-tests.md](writing-tests.md#kafka-assertions) |
| gRPC calls or mocks | `channel<T>`, `wireClient<T>`, and the gRPC Mock DSL |

For example, request counts and captured bodies are not reasons to call WireMock `server().verify(...)` or `server().findAll(...)`: Stove exposes verification and `callsFor`. An HTTP response header or NDJSON assertion does not require `client()`.

## Use native access for an actual gap

For a fallback, leave a brief code comment or implementation note explaining the required capability, the closest API checked, and why it does not cover the requirement. "Advanced usage" alone is not a reason. Keep the fallback local to that operation; continue using Stove for supported operations around it.

Legitimate native use includes:

- **Documented primary APIs:** Redis currently exposes Lettuce through `client()` for its data operations. Close connections the test opens, while leaving the Stove-owned client lifecycle to Stove.
- **Supported extension contracts:** native builders, matchers, statement types, and configuration/migration/cleanup callbacks can be part of the Stove API. Their imports alone do not indicate a bypass. See [system-setup.md](system-setup.md#cleanup) for cleanup timing.
- **Preparation without bypassing execution:** Cassandra needs `session().prepare(...)` to create a bound statement; pass it to Stove's `shouldExecute` or `shouldQuery` instead of executing it through the session.
- **Unsupported operations or semantics:** Couchbase's current `save` methods insert documents; a required upsert is a different operation. Likewise, administration or protocol features can justify native access when the resolved API cannot express them. First check whether setup options or migrations cover infrastructure preparation such as index creation.

Prefer managed native extensions where available:

- **WireMock:** `rawStub` registers native mappings with Stove naming, test scoping, reporting, journaling, and cleanup. Use it only for mapping features missing from the DSL; it assigns its own mapping ID. A raw `server()` call does not provide those managed registration guarantees, and native verification can inspect the server-wide journal instead of Stove's current-test journal.
- **HTTP:** `client { ... }` reports a single "Custom HTTP Client Operation". It does not automatically reproduce typed helper request configuration, correlation headers, or detailed response reports. The returned `client()` has no such report wrapper. Use either only for a verified gap, and inspect the relevant helper before relying on its behavior: correlation support varies by operation.

Review newly introduced handle calls and helper implementations before finishing. Judge the system receiver and purpose, including calls hidden behind extensions or aliases; preserve documented native contracts and any explicit user requirement to use the native API.
