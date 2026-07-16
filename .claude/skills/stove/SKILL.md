---
name: stove
description: Use when configuring, writing, or debugging Stove end-to-end tests; choosing JVM, process, container, or provided-application runners; wiring Stove systems; enabling tracing, dashboard, or MCP; or extending Stove with custom systems.
---

# Stove Skill Router

Use this file as the entrypoint only. Open the focused guide that matches the user's task, then verify API names against local source/examples before writing code or snippets.

## First checks

1. Identify the application-under-test mode:
   - JVM in-process: Spring Boot, Ktor, Micronaut, Quarkus.
   - Host process: Go, Python, Rust, Node.js, or another binary via `processApp` / `goApp`.
   - Container image: any language via `containerApp`.
   - Already running app: staging/dev smoke tests via `providedApplication`.
2. Identify test framework: Kotest uses `StoveKotestExtension()` and `kotest.properties`; JUnit uses `StoveJUnitExtension`.
3. Identify needed systems: HTTP, databases, Kafka, WireMock, gRPC, tracing, dashboard.
4. Check local source when uncertain. Current APIs live under `lib/`, `starters/`, `test-extensions/`, and `tools/stove-cli/`.

## Route by task

| User need | Open |
|---|---|
| Gradle source sets, BOM, `e2eTest`, local artifact ambiguity | `gradle-config.md` |
| JVM setup, system options, provided instances (existing infra), keyed systems (`SystemKey`) | `system-setup.md` |
| Writing `stove {}` assertions and validation DSL | `writing-tests.md` |
| Go or other non-JVM process mode | `other-languages.md`, then `go-setup.md` for Go |
| Docker-image AUT / Testcontainers runner | `container.md` |
| OpenTelemetry setup, Gradle plugin wiring, and trace assertions | `tracing.md`, then `gradle-config.md` for task wiring |
| Stove CLI dashboard and agent triage over MCP | `mcp.md` |
| New Stove system implementation | `custom-systems.md` |
| Integration examples and full flows | `docs/recipes/` |
| Failure diagnosis and common symptoms | `docs/troubleshooting.md` |

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

Run the CLI with `stove`.

| Surface | URL / port |
|---|---|
| UI / REST / MCP | `http://localhost:4040` |
| gRPC event ingestion | `localhost:4041` |
| Database | `~/.stove-dashboard.db` |

In test code, `DashboardSystemOptions.cliHost` defaults to `localhost`; `cliPort` is the gRPC port, so the default is `4041`.

## Guardrails for agents

- Do not use pre-0.20 imports such as `com.trendyol.stove.testing.e2e.*`.
- Do not use removed runner types such as `ContainerAppOptions`, `ProcessAppOptions`, or `GoAppOptions`.
- `ReadinessStrategy.HttpGet` takes `url = "..."`, not `path = "..."`.
- `bridge()` is JVM-framework-specific. Import `com.trendyol.stove.spring.bridge`, `com.trendyol.stove.ktor.bridge`, or `com.trendyol.stove.micronaut.bridge` based on the selected framework.
- `bridge()` is not supported on Quarkus yet; do not invent `com.trendyol.stove.quarkus.bridge`.
- Configure `Stove().with { ... }.run()` once in suite setup, usually `beforeProject()`, not inside each test.
- Keep examples minimal and app-specific. Add only the systems the user actually needs.
- Ktor runners must not block: the app's `run` must start the engine with `wait = false` (a blocking main hangs the suite).
- Mock verifications (`wiremock`/`grpcMock` `shouldHaveBeenCalled`) are point-in-time — do not invent a `within`/timeout parameter. Await async flows with the Kafka `atLeastIn` or HTTP assertion first, then verify the mock.
- gRPC Mock (0.26+): among matching stubs the last registered wins; mixing RPC types for one method fails fast; bidi stubs reject `requestMatcher`. Prefer `MethodDescriptor` overloads and `RequestMatcher.message<T> { ... }` over name strings and byte matchers.

## Known runtime pitfalls (0.25.x)

Stove 0.25+ compiles against kotlinx-coroutines 1.11 and Ktor 3.5. Two failure signatures to recognize (full details in `docs/release-notes/0.25.0.md`):

- `NoSuchMethodError: ... BuildersKt.runBlockingK$default` — the test runtime resolved an older coroutines (usually the Spring Boot BOM pinning 1.8.1, which overrides `resolutionStrategy.force`). Fix for Spring dependency-management users: `extra["kotlin-coroutines.version"] = "1.11.0"` in the module's `build.gradle.kts`; otherwise force via `resolutionStrategy.eachDependency`.
- Multipart stub mismatches — Ktor 3.5 quotes `Content-Disposition` names (`name="file"`). Update exact-match WireMock multipart expectations to the quoted form.

## Behavior changes in 0.26.0

Full details and migration guide in `docs/release-notes/0.26.0.md`. Signatures to recognize when a suite upgrades:

- Kafka tests asserting partition metadata start flaking — `publish` no longer pins partition 0 by default; the configured partitioner decides. Pin `partition` explicitly or use a message key.
- `validate()` newly failing on "unmatched requests" — it now sees untagged and previously-invisible traffic (unknown gRPC methods, bidi calls, headerless HTTP). The failure includes near-miss diffs naming why each candidate stub rejected; it found real unmatched traffic, don't suppress it.
- A gRPC stub that "stopped matching" after adding another — last-registered-wins precedence replaced first-match; the newer stub is intentionally overriding.
- `IllegalArgumentException` at stub registration — mixed RPC types on one method, or a `requestMatcher` on a bidi stub; both now fail fast instead of misbehaving silently.
