<h1 align="center">Stove</h1>

<p align="center">The easiest way of writing e2e/component tests for your back-end API in Kotlin</p>

<p align="center"><img src="./docs/assets/stove_architecture.svg" with="600" /></p>
 
[Check the documentation](https://trendyol.github.io/stove/)

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
