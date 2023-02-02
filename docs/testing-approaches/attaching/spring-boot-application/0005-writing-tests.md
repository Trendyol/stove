# Writing Tests

Here is an example test:

- Validates `http://localhost:$port/hello/index` returns the expected text
- A dependent service with "/example-url" returns 200 status
- Couchbase up and running because we can query `system:keyspaces`

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

!!! note
    `FunSpec`, `shouldContain` infix methods are coming from Kotest

## Replacing dependencies for better testability

When it comes to handling the time, no one wants to wait for 30 minutes for a scheduler job, or for a delayed task to be
able to test it.
In these situations what we need to do is `advancing` the time, or replacing the effect of the time for our needs. This
may require you
to change your code, too. Because, we might need to provide a time-free implementation to an interface, or we might need
to extract it
to an interface if not properly implemented.

For example, in international-service project we have a delayed command executor that accepts a task and a time for it
to delay it until
it is right time to execute. But, in tests we need to replace this behaviour with the time-effect free implementation.

```kotlin
class BackgroundCommandBusImpl // is the class for delayed operations
```

We would like to by-pass the time-bounded logic inside BackgroundCommandBusImpl, and for e2eTest scope we write:

```kotlin
class NoDelayBackgroundCommandBusImpl(
    backgroundMessageEnvelopeDispatcher: BackgroundMessageEnvelopeDispatcher,
    backgroundMessageEnvelopeStorage: BackgroundMessageEnvelopeStorage,
    lockProvider: CouchbaseLockProvider,
) : BackgroundCommandBusImpl(
    backgroundMessageEnvelopeDispatcher,
    backgroundMessageEnvelopeStorage,
    lockProvider
) {

    override suspend fun <TNotification : BackgroundNotification> publish(
        notification: TNotification,
        options: BackgroundOptions,
    ) {
        super.publish(notification, options.withDelay(0))
    }

    override suspend fun <TCommand : BackgroundCommand> send(
        command: TCommand,
        options: BackgroundOptions,
    ) {
        super.send(command, options.withDelay(0))
    }
}
```

Now, it is time to tell to e2eTest system to use NoDelay implementation.

That brings us to initializers.

## Writing a TestInitializer

The tests initializers help you to add test scoped beans, basically you can configure the Spring application from the
test perspective.

e2e Testing has dependencies:

- `ObjectMapper`, you can either provide the ObjectMapper you have already in here we get existing bean by `ref("objectMapper")`
- `TestSystemConsumerInterceptor` is for being able to check consumed messages
- `StoveSpringKafkaProducer` to the able to publish messages to Kafka, so messages can route to the listeners in the application you're testing.

```kotlin
class TestInitializer : BaseApplicationContextInitializer({
    bean<TestSystemConsumerInterceptor>(isPrimary = true)
    bean<StoveSpringKafkaProducer>(isPrimary = true)
    bean<ObjectMapper> { ref("objectMapper") } // "objectMapper" bean name should be in your spring context otherwise it will fail, if not you can provide an instance here.
    // Be sure that, Couchbase, Kafka and other systems share the same serialization strategy.
    bean<NoDelayBackgroundCommandBusImpl>(isPrimary = true) // Optional dependency to alter delayed implementation with 0-wait.
})

fun SpringApplication.addTestDependencies() {
    this.addInitializers(TestInitializer())
}
```

`addTestDependencies` is an extension that helps us to register our dependencies in the application.

```kotlin  hl_lines="4"
.systemUnderTest(
    runner = { parameters ->
        com.trendyol.exampleapp.run(parameters) {
            addTestDependencies()
        }
    },
    withParameters = listOf(
        "logging.level.root=error",
        "logging.level.org.springframework.web=error",
        "spring.profiles.active=default",
        "server.http2.enabled=false",
        "kafka.heartbeatInSeconds=2",
        "kafka.autoCreateTopics=true",
        "kafka.offset=earliest"
    )
)
```
