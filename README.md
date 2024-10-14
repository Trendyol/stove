<h1 align="center">Stove</h1>

<p align="center">The easiest way of writing e2e/component tests for your back-end API in Kotlin</p>

<p align="center"><img src="./docs/assets/stove_architecture.svg" with="600" /></p>

# What is Stove?

Stove is an end-to-end testing framework that spins up physical dependencies and your application all together. So you
have a control over dependencies via Kotlin code.

In the JVM world, thanks to code interoperability, you application code and test can be written with different JVM
languages and can be run together.
For example, you can write your application code with Java and write your tests with Kotlin, or Application code with
Scala and test with Kotlin, etc.
Stove uses this ability and provides a way to write your tests in Kotlin.

Your tests will be infra agnostic, but component aware, so they can use easily necessary physical components with Stove
provided APIs.
All the infra is **pluggable**, and can be added easily. You can also create your own infra needs by using the
abstractions
that Stove provides.
Having said that, the only dependency is `docker` since Stove is
using [testcontainers](https://github.com/testcontainers/testcontainers-java) underlying.

You can use JUnit and Kotest for running the tests. You can run all the tests on your CI, too.
But that needs **DinD(docker-in-docker)** integration.

The medium story about the motivation behind the framework:
[A New Approach to the API End-to-End Testing in Kotlin](https://medium.com/trendyol-tech/a-new-approach-to-the-api-end-to-end-testing-in-kotlin-f743fd1901f5)

_Note: Stove is not a replacement for the unit tests, it is a framework for end-to-end/component tests._

> [!NOTE]
> Some people tend to call these tests as _integration tests_, some call them as _component tests_, some call them as
> end-to-end tests. In this documentation, we will use the term end-to-end tests.
> We think that the **e2e/component tests** term is more serving to the purpose of message we want to convey.

## What is the problem?

In the JVM world, we have a lot of frameworks for the application code, but we don't have a mature framework for
end-to-end/component testing.
The use-cases that led us develop the Stove are to increase the productivity of the developers while keeping the quality
of the codebase high and coherent.

Those use-cases are:
- Kotlin app with Spring-Boot
- Kotlin app with Ktor
- Java app with Spring-Boot
- Java app with Micronaut
- Java app with Quarkus
- Scala app with Spring-Boot

People have different tech stacks and each time when they want to write e2e tests, they need to write a lot of boilerplate code.
Stove is here to solve this problem. It provides a single API to write e2e tests for all the tech stacks.

**Stove unifies the testing experience whatever you use.**

For more info and how to use: [Check the documentation](https://trendyol.github.io/stove/)

> [!WARNING]
> Stove is under development and, despite being heavily tested, its API isn't yet stabilized; _breaking changes
> might happen on minor releases._ However, we will always provide migration guides.

> Report any issue or bug [in the GitHub repository.](https://github.com/Trendyol/stove/issues)

[![codecov](https://codecov.io/gh/Trendyol/stove/graph/badge.svg?token=HcKBT3chO7)](https://codecov.io/gh/Trendyol/stove)

## Supports

Physical dependencies: 

- [x] Kafka
- [x] Couchbase
- [x] HttpClient to make real http calls against the _application under test_
- [x] Wiremock to mock all the external dependencies
- [x] PostgresSql
- [x] ElasticSearch
- [x] MongoDB
- [x] MSSQL
- [x] Redis 

Frameworks:

- [x] Spring
- [x] Ktor
- [ ] Quarkus _(up for grabs)_
- [ ] Micronaut _(up for grabs)_

## Show me the code

[📹 Youtube Session about how to use](https://youtu.be/DJ0CI5cBanc?t=669) _(Turkish)_

### Setting-up all the physical dependencies with application

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

### Testing the entire application with physical dependencies

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
