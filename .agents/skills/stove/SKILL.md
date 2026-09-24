---
name: stove
description: Use when configuring, writing, or debugging Stove end-to-end tests; choosing JVM, process, container, or provided-application runners; wiring Stove systems; enabling tracing, dashboard, or MCP; or extending Stove with custom systems.
---

# Stove Skill Router

Open only the focused guides needed for the user's task. Guide links are relative to this skill directory, wherever it is installed.

## First checks

1. Check the project's resolved Stove version, existing test setup, and Gradle conventions before adding configuration. Installed skills follow upstream `main` and can describe APIs newer than the project's dependencies; do not upgrade dependencies just to match an example.
2. Identify the application-under-test mode:
   - JVM in-process: Spring Boot, Ktor, Micronaut, Quarkus.
   - Host process: Go, Python, Rust, Node.js, or another binary via `processApp` / `goApp`.
   - Container image: any language via `containerApp`.
   - Already running app: staging/dev smoke tests via `providedApplication`.
3. Identify test framework: Kotest uses `StoveKotestExtension()` and `kotest.properties`; JUnit uses `StoveJUnitExtension`. Both need explicit Stove startup and teardown in the framework lifecycle; the reporting extension alone does not launch the app.
4. Identify needed systems: HTTP, databases, Kafka, WireMock, gRPC, tracing, dashboard.
5. Verify uncertain APIs against the resolved version's source artifacts; see [gradle-config.md](gradle-config.md#resolve-api-ambiguity-from-local-artifacts). In a Stove checkout, source also lives under `lib/`, `starters/`, `test-extensions/`, and `server/stove-server/`.

Paths such as `docs/`, `examples/`, and `lib/` in these guides refer to the [Stove repository](https://github.com/Trendyol/stove), not the downstream application. When they are absent locally, consult that repository at the matching release tag, or [published documentation](https://trendyol.github.io/stove/).

## Use Stove APIs before native handles

Use the system DSL for every operation it already supports. Before adding native access such as WireMock `server()`, HTTP `client()`, database `session()` / `operations()`, or a helper built on those handles, inspect the resolved version's relevant public methods, overloads, extensions (including companion extensions), inherited APIs, and builders. Familiarity with the underlying SDK, or not finding one guessed method name, is not evidence of a missing Stove API.

Choose in this order: existing system DSL, configuration or supported callback, managed native extension, then unrestricted native handle for a demonstrated gap. For a fallback, briefly name the required capability and the closest Stove API checked in a code comment or implementation note. Keep operations that Stove supports in its DSL.

Native access is correct when it is the documented API contract: Redis currently uses its client for data operations, and some configuration, migration, and cleanup callbacks receive native types. Do not invent missing wrappers. Before finishing, review new handle calls/imports and helpers for duplicated Stove functionality; inspect their receiver and purpose rather than banning a method name. See [api-selection.md](api-selection.md) for the lookup workflow, common replacements, and valid exceptions.

## Route by task

| User need | Open |
|---|---|
| Gradle source sets, BOM, `e2eTest`, local artifact ambiguity | [gradle-config.md](gradle-config.md) |
| JVM setup, system options, provided instances (existing infra), keyed systems (`SystemKey`) | [system-setup.md](system-setup.md) |
| Writing `stove {}` assertions and validation DSL | [writing-tests.md](writing-tests.md) |
| Choosing an API, using a native handle, or reviewing SDK-based helpers | [api-selection.md](api-selection.md) |
| Go or other non-JVM process mode | [other-languages.md](other-languages.md), then [go-setup.md](go-setup.md) for Go |
| Docker-image AUT / Testcontainers runner | [container.md](container.md) |
| OpenTelemetry setup, Gradle plugin wiring, and trace assertions | [tracing.md](tracing.md), then [gradle-config.md](gradle-config.md) for task wiring |
| Dashboard metadata, shared server, PostgreSQL, retention, or administration | [dashboard.md](dashboard.md) |
| Agent queries and failed-run triage over MCP | [mcp.md](mcp.md) |
| New Stove system implementation | [custom-systems.md](custom-systems.md) |
| Integration examples and full flows | [Stove recipes](https://github.com/Trendyol/stove/tree/main/docs/recipes) |
| Dependency conflicts or upgrade behavior changes | [compatibility.md](compatibility.md) |
| Failure diagnosis and common symptoms | [Stove troubleshooting](https://github.com/Trendyol/stove/blob/main/docs/troubleshooting.md) |

## Current API anchors

Prefer these shapes unless local source proves otherwise:

```kotlin
Stove().with {
    // systems first
    httpClient { HttpClientSystemOptions(baseUrl = "http://localhost:8080") }

    // runner last
    springBoot(
        runner = { params -> com.yourcompany.app.run(params) },
        withParameters = listOf("server.port=8080")
    )
}.run()
```

```kotlin
processApp {
    ProcessApplicationOptions(
        command = listOf("./build/app"),
        target = ProcessTarget.Server(
            port = 8080,
            portEnvVar = "PORT",
            readiness = ReadinessStrategy.HttpGet(url = "http://localhost:8080/health")
        )
    )
}
```

```kotlin
goApp(
    binaryPath = System.getProperty("go.app.binary")
        ?: error("go.app.binary system property not set"),
    target = ProcessTarget.Server(
        port = 8080,
        portEnvVar = "PORT",
        readiness = ReadinessStrategy.HttpGet(url = "http://localhost:8080/health")
    )
)
```

```kotlin
containerApp(
    image = "ghcr.io/acme/app:sha",
    target = ContainerTarget.Server(
        hostPort = 8080,
        internalPort = 8080,
        portEnvVar = "PORT",
        readiness = ReadinessStrategy.HttpGet(url = "http://localhost:8080/health")
    )
)
```

```kotlin
providedApplication {
    ProvidedApplicationOptions(
        readiness = ReadinessStrategy.HttpGet(url = "https://staging.example.com/health")
    )
}
```

## Dashboard and MCP defaults

Run the server with `stove`.

| Surface | Default |
|---|---|
| UI / REST / MCP | `http://localhost:4040` |
| gRPC event ingestion | `localhost:4041` |
| Storage | SQLite at `~/.stove-dashboard.db`; set `--database-url` for PostgreSQL |
| Completed-run retention | `1` per app; set `--retention-runs-per-app`, where `0` means unlimited |
| Administration | dedicated `/admin` page |

In test code, `DashboardIngestion.Grpc(host, port)` defaults to `localhost:4041`. Shared servers intentionally have no authentication or authorization, so expose every Stove surface only on a trusted internal network.

## Guardrails for agents

- Do not use pre-0.20 imports such as `com.trendyol.stove.testing.e2e.*`.
- Do not use removed runner types such as `ContainerAppOptions`, `ProcessAppOptions`, or `GoAppOptions`.
- `ReadinessStrategy.HttpGet` takes `url = "..."`, not `path = "..."`.
- `bridge()` is JVM-framework-specific. Import `com.trendyol.stove.spring.bridge`, `com.trendyol.stove.ktor.bridge`, or `com.trendyol.stove.micronaut.bridge` based on the selected framework.
- `bridge()` is not supported on Quarkus yet; do not invent `com.trendyol.stove.quarkus.bridge`.
- Configure `Stove().with { ... }.run()` in the framework lifecycle, usually `beforeProject()` for Kotest, and pair it with `Stove.stop()` in teardown. Reuse the existing setup rather than starting Stove inside individual tests.
- System `cleanup` callbacks run at `Stove.stop()`, not between tests. Use unique test data or explicit per-test cleanup when tests share state.
- Keep examples minimal and app-specific. Add only the systems the user actually needs.
- Choose either `stove-kafka` or `stove-spring-kafka`; they have overlapping classes and different options. Verify which integration the project uses before copying Kafka examples.
- Every custom Gradle `Test` task needs test classes, runtime classpath, and the test engine configured; see [gradle-config.md](gradle-config.md).
- Container AUTs need addresses reachable from inside the container. Host-mapped database, broker, and tracing endpoints are not automatically valid there; see [container.md](container.md#step-5-networking-strategies).
- Ktor runners must not block: the app's `run` must start the engine with `wait = false` (a blocking main hangs the suite).
- Mock verifications (`wiremock`/`grpcMock` `shouldHaveBeenCalled`) are point-in-time — do not invent a `within`/timeout parameter. Await async flows with the Kafka `atLeastIn` or HTTP assertion first, then verify the mock.
- WireMock (0.26+): prefer the structured `request` / `respond` / `behaviour` DSL for new examples. String verb functions match URL paths; use reusable `RequestSpec`s across stubbing, verification, and `callsFor`, and use `rawStub` before unmanaged `server()` access. No compiler opt-in is required; Stove APIs may evolve in minor releases and release notes provide migration guidance.
- gRPC Mock (0.26+): among matching stubs the last registered wins; mixing RPC types for one method fails fast; bidi stubs reject `requestMatcher`. Prefer `MethodDescriptor` overloads and `RequestMatcher.message<T> { ... }` over name strings and byte matchers.
