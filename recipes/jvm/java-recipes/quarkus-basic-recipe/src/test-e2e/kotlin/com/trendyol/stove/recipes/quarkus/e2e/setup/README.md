# Quarkus Recipe Setup

This package provides Stove integration for the Quarkus recipe. The recipe uses
HTTP and tracing assertions and does not configure CDI Bridge access.

## What It Does

- keeps the public `quarkus(runner, withParameters)` DSL unchanged
- starts Quarkus using its normal `main` entrypoint
- waits for Quarkus startup to complete before tests run
- shuts Quarkus down cleanly after the project
- remains compatible with Stove tracing and failure reporting

## Important Files

| File | Purpose |
|------|---------|
| `StoveConfig.kt` | Kotest project config and Stove systems |
| `stove-quarkus` | `quarkus()` DSL and Quarkus launcher |
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

Stove detects Quarkus startup for both HTTP apps and worker-only apps.

## Tracing

If `stove-tracing` is present and the `e2eTest` task is configured with the
OpenTelemetry Java agent, the recipe can collect spans for Quarkus request flow.

The recipe includes a tracing smoke test that verifies spans are emitted for a real
HTTP request.

## CDI Bridge

Quarkus supports `using<T>` through `com.trendyol.stove.quarkus.bridge`. This
recipe does not configure it. Bridge requires an augmented uber-jar on the shared
test runtime classpath and e2e bean preservation; the isolated fast-jar launcher
does not support it. See the [Quarkus Bridge guide](https://trendyol.github.io/stove/frameworks/quarkus/#bridge).
