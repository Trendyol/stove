<h1 align="center">Stove</h1>

<p align="center">
    The easiest way of writing e2e/component tests for your back-end API in Kotlin
</p>

![Release](https://img.shields.io/maven-central/v/com.trendyol/stove-testing-e2e?versionPrefix=0&label=latest-release&color=blue) [<img src="https://img.shields.io/nexus/s/com.trendyol/stove-testing-e2e?server=https%3A%2F%2Foss.sonatype.org&queryOpt=:v=1.0.0.*&label=latest-snapshot"/>](https://oss.sonatype.org/content/repositories/snapshots/com/trendyol/stove-testing-e2e/) [![codecov](https://codecov.io/gh/Trendyol/stove/graph/badge.svg?token=HcKBT3chO7)](https://codecov.io/gh/Trendyol/stove)



<p align="center"><img src="./docs/assets/stove_architecture.svg" with="600" /></p>

# What is Stove?

Stove is a powerful end-to-end testing framework that simplifies testing by managing physical dependencies and your
application in a unified way.
Write infrastructure-agnostic but component-aware tests in Kotlin, regardless of your JVM-based tech stack.

### Key Features

* 🚀 Zero Boilerplate: Write clean, focused tests without infrastructure setup code
* 🔌 Pluggable Architecture: Easily extend with custom infrastructure components
* 🐳 Docker-Based: Leverages Testcontainers for reliable, isolated test environments
* 🌐 Framework Agnostic: Works with Spring, Ktor, and other JVM frameworks _(up for grabs)_
* 🔄 Physical Dependencies: Built-in support for Kafka, Couchbase, PostgreSQL, and more
* ⚡ Fast Development: On top of the boilerplate free testing, optional Reuse test containers for blazing-fast local
  development

## Supported Infrastructure

Physical dependencies:

* ✅ Kafka
* ✅ Couchbase
* ✅ PostgreSQL
* ✅ ElasticSearch
* ✅ MongoDB
* ✅ MSSQL
* ✅ Redis
* ✅ HTTP Client
* ✅ WireMock

Frameworks:

* ✅ Spring
* ✅ Ktor
* 🚧 Quarkus (up for grabs)
* 🚧 Micronaut (up for grabs)

## Quick Start

### Add the dependency

```kotlin
testImplementation("com.trendyol:stove-testing-e2e:${version}")
// and the one of the following for the infrastructure you want to use
testImplementation("com.trendyol:stove-testing-e2e-kafka:${version}")
// and Application Under Test (AUT)
testImplementation("com.trendyol:stove-ktor-testing-e2e:${version}")
//or
testImplementation("com.trendyol:stove-spring-testing-e2e:${version}")
```

### Set Up the TestSystem

```kotlin
TestSystem() {
  if (isRunningLocally()) {
    enableReuseForTestContainers()

    // this will keep the dependencies running
    // after the tests are finished,
    // so next run will be blazing fast :)
    keepDendenciesRunning()
  }
}.with {
  // Enables http client 
  // to make real http calls 
  // against the application under test
  httpClient {
    HttpClientSystemOptions(
      baseUrl = "http://localhost:8001",
    )
  }

  // Enables Couchbase physically 
  // and exposes the configuration 
  // to the application under test
  couchbase {
    CouchbaseSystemOptions(
      defaultBucket = "Stove",
      configureExposedConfiguration = { cfg -> listOf("couchbase.hosts=${cfg.hostsWithPort}") },
    )
  }

  // Enables Kafka physically 
  // and exposes the configuration 
  // to the application under test
  kafka {
    KafkaSystemOptions(
      configureExposedConfiguration = { cfg -> listOf("kafka.bootstrapServers=${cfg.boostrapServers}") },
    )
  }

  // Enables Wiremock on the given port 
  // and provides configurable mock HTTP server 
  // for your external API calls
  wiremock {
    WireMockSystemOptions(
      port = 9090,
      removeStubAfterRequestMatched = true,
      afterRequest = { e, _, _ ->
        logger.info(e.request.toString())
      },
    )
  }

  // The Application Under Test. 
  // Enables Spring Boot application 
  // to be run with the given parameters.
  springBoot(
    runner = { parameters ->
      stove.spring.example.run(parameters) { it.addTestSystemDependencies() }
    },
    withParameters = listOf(
      "server.port=8001",
      "logging.level.root=warn",
      "logging.level.org.springframework.web=warn",
      "spring.profiles.active=default",
      "kafka.heartbeatInSeconds=2",
    ),
  )
}.run()

```

### Write Tests

```kotlin
TestSystem.validate {
  wiremock {
    mockGet("/example-url", responseBody = None, statusCode = 200)
  }

  http {
    get<String>("/hello/index") { actual ->
      actual shouldContain "Hi from Stove framework"
      println(actual)
    }
  }

  couchbase {
    shouldQuery<Any>("SELECT * FROM system:keyspaces") { actual ->
      println(actual)
    }
  }

  kafka {
    shouldBePublished<ExampleMessage> {
      actual.aggregateId == 123
          && metadata.topic = "example-topic"
          && metadata.headers["example-header"] == "example-value"
    }
    shouldBeConsumed<ExampleMessage> {
      actual.aggregateId == 123
          && metadata.topic = "example-topic"
          && metadata.headers["example-header"] == "example-value"
    }
  }

  couchbase {
    save(collection = "Backlogs", id = "id-of-backlog", instance = Backlog("id-of-backlog"))
  }

  http {
    postAndExpectBodilessResponse("/backlog/reserve") { actual ->
      actual.status.shouldBe(200)
    }
  }

  kafka {
    shouldBeConsumed<ProductCreated> {
      actual.aggregateId == expectedId
    }
  }
}
```

## Why Stove?

The JVM ecosystem lacks a unified approach to end-to-end testing.
While tools like Testcontainers exist, developers still need to:

* Write extensive boilerplate code
* Complex setup code for each tech stack
* Create different testing setups for each framework
* Manage complex infrastructure configurations for each framework

This affects teams across many tech stacks:

* Kotlin with Spring Boot/Ktor
* Java with Spring Boot/Micronaut/Quarkus
* Scala with Spring Boot

Stove solves these challenges by providing:

* A unified testing API across all JVM stacks
* Built-in support for common infrastructure
* Clean, Kotlin-based test syntax
* Reusable test containers for fast local development

**Stove unifies the testing experience across all JVM stacks, making it easier to write clean, focused tests.**

## Resources

* 📖 [Documentation](https://trendyol.github.io/stove/)
* [📹 Youtube Session about how to use](https://youtu.be/DJ0CI5cBanc?t=669) _(Turkish)_
* 📝 [Motivation Article](https://medium.com/trendyol-tech/a-new-approach-to-the-api-end-to-end-testing-in-kotlin-f743fd1901f5)

## Status

> [!WARNING]
> While Stove is production-ready and extensively tested, the API is not yet fully stabilized. Breaking changes may
> occur
> in minor releases, but migration guides will always be provided.

## Contributing

Contributions are welcome! Whether it's:

* 🐛 [Bug reports](https://github.com/Trendyol/stove/issues)
* 💡 [Feature requests](https://github.com/Trendyol/stove/issues)
* 📖 Documentation improvements
* 🚀 Code contributions

## License

Stove is licensed under the Apache License, Version 2.0. See [LICENSE](LICENSE) for the full license text.
