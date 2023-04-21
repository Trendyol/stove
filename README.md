# stove4k
Stove: The easiest way of writing e2e tests for your back-end API in Kotlin

![](./docs/assets/stove_architecture.svg)

[Check the documentation](https://trendyol.github.io/stove4k/)

## Supports

Physical dependencies: 

- [x] Kafka `withKafka`
- [x] Couchbase `withCouchbase`
- [x] HttpClient to make real http calls against the _application under test_
- [x] Wiremock `withWiremock` to mock all the external dependencies
- [x] PostgresSql `withPostgresql`
- [x] ElasticSearch `withElasticsearch`
- [x] MongoDB `withMongodb`

Frameworks:

- [x] Spring
- [x] Ktor
- [ ] Quarkus _(up for grabs)_

## Show me the code

### Setting-up all the physical dependencies with application

```kotlin
TestSystem(baseUrl = "http://localhost:8001") {
    if (isRunningLocally()) {
        enableReuseForTestContainers()
        keepDendenciesRunning() // this will keep the dependencies running after the tests are finished, so next run will be blazing fast :)
    }
}.with {
    httpClient() // Enables http client to make real http calls against the application under test

    couchbase { // Enables Couchbase physically and exposes the configuration to the application under test
        CouchbaseSystemOptions(
            defaultBucket = "Stove",
            configureExposedConfiguration = { cfg -> listOf("couchbase.hosts=${cfg.hostsWithPort}") },
        )
    }

    kafka { // Enables Kafka physically and exposes the configuration to the application under test
        KafkaSystemOptions(
            configureExposedConfiguration = { cfg -> listOf("kafka.bootstrapServers=${cfg.boostrapServers}") },
        )
    }

    wiremock { // Enables Wiremock on the given port and provides configurable mock HTTP server for your external API calls
        WireMockSystemOptions(
            port = 9090,
            removeStubAfterRequestMatched = true,
            afterRequest = { e, _, _ ->
                logger.info(e.request.toString())
            },
        )
    }

    springBoot( // The Application Under Test. Enables Spring Boot application to be run with the given parameters.
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
        shouldBePublishedOnCondition<ExampleMessage> { actual ->
            actual.aggregateId == 123
        }
        shouldBeConsumedOnCondition<ExampleMessage> { actual ->
            actual.aggregateId == 123
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
        shouldBeConsumedOnCondition<ProductCreated> { actual ->
            actual.aggregateId == expectedId
        }
    }
}
```
