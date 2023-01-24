# stove4k
Stove: The easiest way of e2e testing in Kotlin

[Check the documentation](https://trendyol.github.io/stove4k/)

## Roadmap

Stove implements all the physical dependencies, and you can write end-to-end tests against them;

Physical dependencies: 

- [x] Kafka implementation `withKafka`
- [x] Couchbase implementation `withCouchbase`
- [x] Default Http to make real http calls against the _application under test_
- [x] Wiremock `withWiremock` to mock all the external dependencies
- [ ] ElasticSearch implementation `withElasticSearch`
- [ ] PostgresSql implementation `withPostgresSql`

Framework support:

- [x] Spring
- [ ] Ktor
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
// Testing
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
    .shouldBePublished<ExampleMessage> { actual ->
        actual.aggregateId shouldBe 123
    }
    .shouldBeSuccessfullyConsumed<ExampleMessage> { actual ->
        actual.aggregateId shouldBe 123
    }
    .then().couchbase().save(collection = "Backlogs", id = "id-of-backlog", instance = Backlog("id-of-backlog"))
    .then().defaultHttp().postAndExpectBodilessResponse("/backlog/reserve") { actual ->
        actual.status.shouldBe(200)
    }
    .then().kafka().shouldBeConsumedOnCondition<ProductCreated> { actual ->
        actual.aggregateId == expectedId
    }
```
