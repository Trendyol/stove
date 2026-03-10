# Supported Frameworks

Stove keeps the testing model consistent across frameworks, but application startup is framework-specific. Pick the starter that matches your runtime, then keep the rest of the test DSL the same.

## Pick Your Starter

<div class="grid cards" markdown>

-   :material-sprout: **Spring Boot**

    For applications built on Spring Boot. Supports `bridge()` for direct bean access.

    [Open the Spring Boot guide](spring-boot.md)

-   :material-lightning-bolt: **Ktor**

    For applications built on Ktor. Supports `bridge()` for direct bean access.

    [Open the Ktor guide](ktor.md)

-   :material-hexagon-outline: **Micronaut**

    For applications built on Micronaut. Supports `bridge()` for direct bean access.

    [Open the Micronaut guide](micronaut.md)

-   :material-fire: **Quarkus**

    For applications built on Quarkus. `bridge()` is not available yet.

    [Open the Quarkus guide](quarkus.md)

</div>

## How to Choose

Pick the starter that matches your application's framework — that's it. The test DSL, components, and assertions work the same way regardless of which starter you use.

- **`bridge()` support**: available in Spring Boot, Ktor, and Micronaut starters. Not yet available in Quarkus.

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
