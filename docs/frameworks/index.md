# Supported Frameworks

Stove keeps the testing model consistent across frameworks, but the application entrypoint is framework-specific. Start here if you already know what your application is built with.

## Pick Your Starter

<div class="grid cards" markdown>

-   :material-sprout: **Spring Boot**

    Best fit if you want the richest Stove integration, including `bridge()` support.

    [Open the Spring Boot guide](spring-boot.md)

-   :material-lightning-bolt: **Ktor**

    Great for lightweight services and custom DI setups. `bridge()` is supported.

    [Open the Ktor guide](ktor.md)

-   :material-hexagon-outline: **Micronaut**

    Strong fit for AOT-friendly services and fast startup. `bridge()` is supported.

    [Open the Micronaut guide](micronaut.md)

-   :material-fire: **Quarkus**

    Best when you want same-JVM tracing and reporting. `bridge()` is not available yet.

    [Open the Quarkus guide](quarkus.md)

</div>

## At A Glance

| Framework | Starter | Bridge | Example |
|-----------|---------|--------|---------|
| Spring Boot | `stove-spring` | Yes | [spring-example](https://github.com/Trendyol/stove/tree/main/examples/spring-example) |
| Ktor | `stove-ktor` | Yes | [ktor-example](https://github.com/Trendyol/stove/tree/main/examples/ktor-example) |
| Micronaut | `stove-micronaut` | Yes | [micronaut-example](https://github.com/Trendyol/stove/tree/main/examples/micronaut-example) |
| Quarkus | `stove-quarkus` | Not yet | [quarkus-example](https://github.com/Trendyol/stove/tree/main/examples/quarkus-example) |

## What Stays The Same

No matter which starter you pick:

- Stove still starts your physical dependencies first
- component configuration still comes from the same `Stove().with { ... }` DSL
- reporting and tracing still work the same way
- you can mix components such as Kafka, PostgreSQL, WireMock, HTTP, gRPC, and Redis

If you are new to Stove, start with [Getting Started](../getting-started.md) first, then come back here to pick the framework-specific setup.
