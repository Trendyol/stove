# Kafka

There might be a **potential improvement** on the configuration for better testing results that can improve the testing
performance.

If you have the following configurations:

- `AUTO_OFFSET_RESET_CONFIG | "auto.offset.reset"`
- `ALLOW_AUTO_CREATE_TOPICS_CONFIG | "allow.auto.create.topics"`
- `HEARTBEAT_INTERVAL_MS_CONFIG | "heartbeat.interval.ms"`

You better make them configurable, so from the e2e testing context we can change them to improve testing performance.

As an example:

```kotlin
TestSystem()
    .withDefaultHttp()
    .withCouchbase("International")
    .withKafka()
    .withWireMock(9090)
    .withSchedulerSystem()
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
    ).run()
```
