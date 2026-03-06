# Supported Frameworks

Stove keeps the testing model consistent across frameworks, but application startup is framework-specific. Pick the starter that matches your runtime, then keep the rest of the test DSL the same.

## Pick Your Starter

<div class="grid cards" markdown>

-   :material-sprout: **Spring Boot**

    The most established Stove path. Good default if you want strong `bridge()` support and familiar Spring startup.

    [Open the Spring Boot guide](spring-boot.md)

-   :material-lightning-bolt: **Ktor**

    Best when you want explicit server bootstrap and full control over DI and wiring.

    [Open the Ktor guide](ktor.md)

-   :material-hexagon-outline: **Micronaut**

    Close to the Spring-style experience, but with Micronaut's application context and startup model.

    [Open the Micronaut guide](micronaut.md)

-   :material-fire: **Quarkus**

    Use this when you want same-JVM tracing and reporting with a Quarkus app. `bridge()` is not available yet.

    [Open the Quarkus guide](quarkus.md)

</div>

## Quick Decisions

- Need `bridge()` bean access in tests: choose Spring Boot, Ktor, or Micronaut.
- Need Quarkus startup plus same-JVM tracing/reporting: choose Quarkus.
- Want the most familiar path for a typical enterprise JVM service: start with Spring Boot.
- Want the most explicit application bootstrap: start with Ktor.

## At A Glance

| Framework | Starter | Entrypoint style | Bridge | Example |
|-----------|---------|------------------|--------|---------|
| Spring Boot | `stove-spring` | `runApplication(...)` wrapped in `run(args)` | Yes | [spring-example](https://github.com/Trendyol/stove/tree/main/examples/spring-example) |
| Ktor | `stove-ktor` | `embeddedServer(...)` wrapped in `run(args)` | Yes | [ktor-example](https://github.com/Trendyol/stove/tree/main/examples/ktor-example) |
| Micronaut | `stove-micronaut` | `ApplicationContext` startup wrapped in `run(args)` | Yes | [micronaut-example](https://github.com/Trendyol/stove/tree/main/examples/micronaut-example) |
| Quarkus | `stove-quarkus` | `@QuarkusMain` entrypoint plus `Quarkus.run(*args)` | Not yet | [quarkus-example](https://github.com/Trendyol/stove/tree/main/examples/quarkus-example) |

## What Stays The Same

No matter which starter you pick:

- Stove starts your physical dependencies first
- component configuration still comes from the same `Stove().with { ... }` DSL
- reporting and tracing still integrate the same way
- you can mix Kafka, PostgreSQL, WireMock, HTTP, gRPC, Redis, and other components

If you are new to Stove, start with [Getting Started](../getting-started.md) first, then come back here to pick the framework-specific setup.
