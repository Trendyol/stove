# Kafka

When you want to use Kafka with Application Aware testing it provides more assertion capabilities. It is recommended way of working.
Stove-Kafka does that with intercepting the messages.

## How to get?

=== "Gradle"

    ``` kotlin
        dependencies {
          testImplementation("com.trendyol:stove-spring-testing-e2e-kafka:$version")
        }
    ```

=== "Maven"

    ```xml
     <dependency>
        <groupId>com.trendyol</groupId>
        <artifactId>stove-spring-testing-e2e-kafka</artifactId>
        <version>${stove-version}</version>
     </dependency>
    ```

## Configure

### Configuration Values

Kafka works with some settings as default, your application might have these values as not configurable, to make the application testable we need to tweak a little bit.

If you have the following configurations:

- `AUTO_OFFSET_RESET_CONFIG | "auto.offset.reset" | should be "earliest"`
- `ALLOW_AUTO_CREATE_TOPICS_CONFIG | "allow.auto.create.topics" | should be true`
- `HEARTBEAT_INTERVAL_MS_CONFIG | "heartbeat.interval.ms" | should be 2 seconds`

You better make them configurable, so from the e2e testing context we can change them work with Stove-Kafka testing.

As an example:

```kotlin
TestSystem()
    .withHttpClient() 
    .withKafka()
    .systemUnderTest(
        runner = { parameters ->
            com.trendyol.exampleapp.run(parameters)
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

As you can see, we pass these configuration values as parameters. Since they are configurable, the application considers these values instead of application-default values.

### Consumer Settings

Second thing we need to do is tweak your consumer configuration. For that we will provide Stove-Kafka interceptor to your Kafka configuration.

Locate to the point where you define your `ConcurrentKafkaListenerContainerFactory` or where you can set the interceptor. Interceptor needs to implement `ConsumerAwareRecordInterceptor<String, String>` since
Stove-Kafka [relies on that](https://github.com/Trendyol/stove4k/blob/main/starters/spring/stove-spring-testing-e2e-kafka/src/main/kotlin/com/trendyol/stove/testing/e2e/kafka/TestSystemInterceptor.kt).

```kotlin
@EnableKafka
@Configuration
class KafkaConsumerConfiguration(
    private val interceptor: ConsumerAwareRecordInterceptor<String, String>,
) {

    @Bean
    fun kafkaListenerContainerFactory(): ConcurrentKafkaListenerContainerFactory<String, String> {
        val factory = ConcurrentKafkaListenerContainerFactory<String, String>()
        // ...
        factory.setRecordInterceptor(interceptor)
        return factory
    }
}
```

### Producer Settings

Make sure that the [aforementioned](#configuration-values) values are also configureable for producer settings, too.
Stove will have access to `KafkaTemplate` and will use `setProducerListener` to arrange itself to listen produced messages.

### Plugging in

When all the configuration is done, it is time to tell to application to use our `TestSystemInterceptor` and configuration values.

#### TestSystemInterceptor and TestInitializer

```kotlin
class TestInitializer : BaseApplicationContextInitializer({
    bean<TestSystemInterceptor>(isPrimary = true)
})

fun SpringApplication.addTestDependencies() {
    this.addInitializers(TestInitializer())
}
```

#### Configuring the SystemUnderTest and Parameters

`addTestDependencies` is an extension that helps us to register our dependencies in the application.

```kotlin  hl_lines="4"
.systemUnderTest(
    runner = { parameters ->
        com.trendyol.exampleapp.run(parameters) {
            addTestDependencies() // Enable TestInitializer with extensions call
        }
    },
    withParameters = listOf(
        "logging.level.root=error",
        "logging.level.org.springframework.web=error",
        "spring.profiles.active=default",
        "server.http2.enabled=false",
        "kafka.heartbeatInSeconds=2", // Added Parameter
        "kafka.autoCreateTopics=true", // Added Parameter
        "kafka.offset=earliest" // Added Parameter
    )
)
```

Now you're full set and have control over Kafka messages from the testing context.

```kotlin
TestSystem
    .instance
    .kafka()
    .shouldBeConsumedOnCondition<AnyEvent> { actual->  }
    .shouldBePublishedOnCondition<AnyEvent> { actual->  }
```
