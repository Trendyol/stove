# Stove

Stove is an end-to-end testing framework for JVM applications. It boots your real application together with the dependencies it actually uses, so your tests exercise the real runtime flow instead of a hand-built harness full of mocks.

If your service talks to HTTP APIs, Kafka, databases, Redis, gRPC services, or external providers, Stove lets you bring those pieces into one test setup and assert the full behavior in one place.

Since JVM languages interoperate, your application and tests do not need to use the same language. Write the app in Java, Kotlin, or Scala, and keep the tests consistent on the Stove side.

The only hard requirement is <span data-rn="underline" data-rn-color="#ff9800">Docker</span>, because Stove uses [Testcontainers](https://github.com/testcontainers/testcontainers-java) under the hood.

!!! note "Not a Replacement for Unit Tests"
    Stove is for end-to-end and component tests, not unit tests. Keep unit tests for fast feedback on isolated logic.

## See It Quickly

The core idea is small:

```kotlin
Stove()
  .with {
    httpClient {
      HttpClientSystemOptions(baseUrl = "http://localhost:8080")
    }

    kafka {
      KafkaSystemOptions(...)
    }

    springBoot(
      runner = { params -> run(params) },
      withParameters = listOf("server.port=8080")
    )
  }
  .run()

stove {
  http {
    get<String>("/hello") { body ->
      body shouldContain "hello"
    }
  }

  kafka {
    shouldBePublished<String> { it.contains("created") }
  }
}
```

You start the real app, bring up only the dependencies you need, and assert through the surfaces that matter.

## Choose Your Path

<div class="grid cards" markdown>

-   **New to Stove**

    Start with the shared setup model and learn the basic DSL once.

    [Getting Started](getting-started.md)

-   **Already know your framework**

    Pick the starter that matches your application runtime.

    [Supported Frameworks](frameworks/index.md)

-   **Already know your dependencies**

    Add Kafka, PostgreSQL, WireMock, HTTP, tracing, and other components as needed.

    [Components](Components/index.md)

-   **Want a working project**

    Open a complete example and adapt it instead of starting from scratch.

    [Examples on GitHub](https://github.com/Trendyol/stove/tree/main/examples)

</div>

## Why Stove

The JVM ecosystem has strong application frameworks, but e2e setup is usually framework-specific and repetitive. Teams end up rebuilding the same boilerplate around containers, startup wiring, ports, config injection, test cleanup, and diagnostics.

Stove standardizes that workflow:

- start physical dependencies first
- boot the real application through its actual entrypoint
- inject container/runtime configuration into the app
- assert through HTTP, Kafka, gRPC, databases, and tracing
- keep the same test DSL across frameworks

<span data-rn="highlight" data-rn-color="#00968855" data-rn-duration="800">One testing model, multiple JVM stacks.</span>

## Supported Frameworks

Stove currently ships starters for:

- [Spring Boot](frameworks/spring-boot.md)
- [Ktor](frameworks/ktor.md)
- [Micronaut](frameworks/micronaut.md)
- [Quarkus](frameworks/quarkus.md)

See the full overview in [Supported Frameworks](frameworks/index.md), including `bridge()` availability and example links.

## What You Can Test

Stove composes framework starters with pluggable components, so you can match your test environment to your production architecture.

- APIs through [HTTP](Components/05-http.md) or [gRPC](Components/12-grpc.md)
- event flows through [Kafka](Components/02-kafka.md)
- persistence through [PostgreSQL](Components/06-postgresql.md), [MySQL](Components/16-mysql.md), [MongoDB](Components/07-mongodb.md), [Redis](Components/09-redis.md), and more
- external integrations through [WireMock](Components/04-wiremock.md) and [gRPC Mock](Components/14-grpc-mock.md)
- execution diagnostics through [Reporting](Components/13-reporting.md) and [Tracing](Components/15-tracing.md)

## High Level Architecture

![Stove architecture](./assets/stove_architecture.svg)

## Start Here

1. Read [Getting Started](getting-started.md) for the shared setup.
2. Open your starter guide under [Supported Frameworks](frameworks/index.md).
3. Add the components you need from [Components](Components/index.md).
4. Compare against a real project in [examples](https://github.com/Trendyol/stove/tree/main/examples).

## Building From Source

To build Stove locally you need:

- JDK 17+
- Docker

Then run:

```shell
./gradlew build
```

Want the background and motivation? Read the original [Medium article](https://medium.com/trendyol-tech/a-new-approach-to-the-api-end-to-end-testing-in-kotlin-f743fd1901f5).
