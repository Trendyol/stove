# Quarkus Recipe Setup

This package provides Stove integration for the Quarkus recipe without relying on
Quarkus bean bridging.

## What It Does

- keeps the public `quarkus(runner, withParameters)` DSL unchanged
- starts Quarkus by calling the provided `main` runner on a background thread
- waits for an explicit Quarkus startup signal before tests run
- shuts Quarkus down cleanly after the project
- remains compatible with Stove tracing and failure reporting

## Important Files

| File | Purpose |
|------|---------|
| `StoveConfig.kt` | Kotest project config and Stove systems |
| `QuarkusSystem.kt` | `quarkus()` DSL and direct-main launcher |
| `IndexTests.kt` | HTTP smoke test and tracing smoke test |

## Usage

```kotlin
quarkus(
  runner = { params ->
    QuarkusMainApp.main(params)
  },
  withParameters = listOf(
    "quarkus.http.port=8040"
  )
)
```

The DSL shape stays the same and the recipe invokes `QuarkusMainApp.main(...)`
from a dedicated launcher thread. Quarkus readiness is based on an application
startup signal, so the launcher can work for both HTTP apps and worker-only apps.

## Tracing

If `stove-tracing` is present and the `e2eTest` task is configured with the
OpenTelemetry Java agent, the recipe can collect spans for Quarkus request flow.

The recipe includes a tracing smoke test that verifies spans are emitted for a real
HTTP request.

## Non-Goals

- No `using<T>` bridge support for Quarkus beans
- No cross-classloader bean access
- No Quarkus-specific DI adapter in this recipe
