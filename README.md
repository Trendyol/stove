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

Frameworks:

- [x] Spring
- [x] Ktor
- [ ] Quarkus

## Show me the code

### Setting-up all the physical dependencies with application

```kotlin
TestSystem(baseUrl = "http://localhost:8001")
    .withDefaultHttp()
    .withCouchbase(
        bucket = "Stove",
        options = CouchbaseSystemOptions(
            configureExposedConfiguration = { cfg -> listOf("couchbase.hosts=${cfg.hostsWithPort}") }
        )
    )
    .withKafka(
        configureExposedConfiguration = { cfg ->
            listOf("kafka.bootstrapServers=${cfg.boostrapServers}")
        }
    )
    .withWireMock(
        port = 9090,
        WireMockSystemOptions(
            removeStubAfterRequestMatched = true,
            afterRequest = { e, _, _ ->
                logger.info(e.request.toString())
            }
        )
    )
    .systemUnderTest(
        runner = { parameters ->
            stove.spring.example.run(parameters) { it.addTestSystemDependencies() }
        },
        withParameters =
        listOf(
            "server.port=8001",
            "logging.level.root=warn",
            "logging.level.org.springframework.web=warn",
            "spring.profiles.active=default",
            "kafka.heartbeatInSeconds=2"
        )
    )
    .run()
```

### Testing the entire application with physical dependencies

```kotlin
TestSystem.instance
    .defaultHttp().get<String>("/hello/index") { actual ->
        actual shouldContain "Hi from Stove framework"
        println(actual)
    }
    .then().wiremock().mockGet("/example-url", responseBody = None, statusCode = 200)
    .then().couchbase().shouldQuery<Any>("SELECT * FROM system:keyspaces") { actual ->
        println(actual)
    }
    .then().kafka()
    .shouldBePublishedOnCondition<ExampleMessage> { actual ->
        actual.aggregateId == 123
    }
    .shouldBeConsumedOnCondition<ExampleMessage> { actual ->
        actual.aggregateId == 123
    }
    .then().couchbase().save(collection = "Backlogs", id = "id-of-backlog", instance = Backlog("id-of-backlog"))
    .then().http().postAndExpectBodilessResponse("/backlog/reserve") { actual ->
        actual.status.shouldBe(200)
    }
    .then().kafka().shouldBeConsumedOnCondition<ProductCreated> { actual ->
        actual.aggregateId == expectedId
    }
```
