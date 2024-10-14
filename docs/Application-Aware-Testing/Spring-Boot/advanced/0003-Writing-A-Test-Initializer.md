# Writing a TestInitializer

The tests initializers help you to add test scoped beans, basically you can configure the Spring application from the
test perspective.

e2e Testing has dependencies:

- `ObjectMapper`, you can either provide the ObjectMapper you have already in here we get existing bean by `ref("objectMapper")`
- `TestSystemInterceptor` is for being able to check consumed messages

```kotlin
class TestInitializer : BaseApplicationContextInitializer({
    bean<TestSystemInterceptor>(isPrimary = true)
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
