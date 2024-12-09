# Kafka

There are two ways to work with Kafka in Stove. You can use standalone Kafka or Kafka with Spring. You can use only one
of them in your project.

## Standalone Kafka

=== "Gradle"

    ``` kotlin
        dependencies {
            testImplementation("com.trendyol:stove-testing-e2e-kafka:$version")
        }
    ```

### Configure

```kotlin
TestSystem()
  .with {
    // other dependencies

    kafka {
      stoveKafkaObjectMapperRef = objectMapperRef
      KafkaSystemOptions {
        listOf(
          "kafka.bootstrapServers=${it.bootstrapServers}",
          "kafka.interceptorClasses=${it.interceptorClass}"
        )
      }
    }
  }.run()
```

The configuration values are:

```kotlin
class KafkaSystemOptions(
  /**
   * Suffixes for error and retry topics in the application.
   */
  val topicSuffixes: TopicSuffixes = TopicSuffixes(),
  /**
   * If true, the system will listen to the messages published by the Kafka system.
   */
  val listenPublishedMessagesFromStove: Boolean = false,
  /**
   * The port of the bridge gRPC server that is used to communicate with the Kafka system.
   */
  val bridgeGrpcServerPort: Int = stoveKafkaBridgePortDefault.toInt(),
  /**
   * The Serde that is used while asserting the messages,
   * serializing while bridging the messages. Take a look at the [serde] property for more information.
   *
   * The default value is [StoveSerde.jackson]'s anyByteArraySerde.
   * Depending on your application's needs you might want to change this value.
   *
   * The places where it was used listed below:
   *
   * @see [com.trendyol.stove.testing.e2e.standalone.kafka.intercepting.StoveKafkaBridge] for bridging the messages.
   * @see StoveKafkaValueSerializer for serializing the messages.
   * @see StoveKafkaValueDeserializer for deserializing the messages.
   * @see valueSerializer for serializing the messages.
   */
  val serde: StoveSerde<Any, ByteArray> = stoveSerdeRef,
  /**
   * The Value serializer that is used to serialize messages.
   */
  val valueSerializer: Serializer<Any> = StoveKafkaValueSerializer(),
  /**
   * The options for the Kafka container.
   */
  val containerOptions: KafkaContainerOptions = KafkaContainerOptions(),
  /**
   * The options for the Kafka system that is exposed to the application
   */
  override val configureExposedConfiguration: (KafkaExposedConfiguration) -> List<String>
) : SystemOptions, ConfiguresExposedConfiguration<KafkaExposedConfiguration>
```

### Configuring Serializer and Deserializer

Like every `SystemOptions` object, `KafkaSystemOptions` has a `serde` property that you can configure. It is a
`StoveSerde` object that has two functions `serialize` and `deserialize`. You can configure them depending on your
application's needs.

```kotlin
val kafkaSystemOptions = KafkaSystemOptions(
  serde = object : StoveSerde<Any, ByteArray> {
    override fun serialize(value: Any): ByteArray {
      return objectMapper.writeValueAsBytes(value)
    }

    override fun <T> deserialize(value: ByteArray): T {
      return objectMapper.readValue(value, Any::class.java) as T
    }
  }
)
```

### Kafka Bridge With Your Application

Stove Kafka bridge is a **MUST** to work with Kafka. Otherwise you can't assert any messages from your application.

As you can see in the example above, you need to add a support to your application to work with interceptor that Stove
provides.

```kotlin
 "kafka.interceptorClasses=com.trendyol.stove.testing.e2e.standalone.kafka.intercepting.StoveKafkaBridge"

// or

"kafka.interceptorClasses={cfg.interceptorClass}" // cfg.interceptorClass is exposed by Stove
```

!!! Important

    `kafka.` prefix or `interceptorClasses` are assumptions that you can change it with your own prefix or configuration.

## Spring Kafka

When you want to use Kafka with Application Aware testing it provides more assertion capabilities. It is recommended way
of working. Stove-Kafka does that with intercepting the messages.

### How to get?

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

### Configure

#### Configuration Values

Kafka works with some settings as default, your application might have these values as not configurable, to make the
application testable we need to tweak a little bit.

If you have the following configurations:

- `AUTO_OFFSET_RESET_CONFIG | "auto.offset.reset" | should be "earliest"`
- `ALLOW_AUTO_CREATE_TOPICS_CONFIG | "allow.auto.create.topics" | should be true`
- `HEARTBEAT_INTERVAL_MS_CONFIG | "heartbeat.interval.ms" | should be 2 seconds`

You better make them configurable, so from the e2e testing context we can change them work with Stove-Kafka testing.

As an example:

```kotlin
TestSystem()
  .with {
    httpClient()
    kafka()
    springBoot(
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
    )
  }.run()
```

As you can see, we pass these configuration values as parameters. Since they are configurable, the application considers
these values instead of application-default values.

### Consumer Settings

Second thing we need to do is tweak your consumer configuration. For that we will provide Stove-Kafka interceptor to
your Kafka configuration.

Locate to the point where you define your `ConcurrentKafkaListenerContainerFactory` or where you can set the
interceptor. Interceptor needs to implement `ConsumerAwareRecordInterceptor<String, String>` since
Stove-Kafka [relies on that](https://github.com/Trendyol/stove/blob/main/starters/spring/stove-spring-testing-e2e-kafka/src/main/kotlin/com/trendyol/stove/testing/e2e/kafka/TestSystemInterceptor.kt).

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

Make sure that the [aforementioned](#configuration-values) values are also configurable for producer settings, too.
Stove will have access to `KafkaTemplate` and will use `setProducerListener` to arrange itself to listen produced
messages.

### Plugging in

When all the configuration is done, it is time to tell to application to use our `TestSystemInterceptor` and
configuration values.

#### TestSystemInterceptor and TestInitializer

```kotlin
class TestInitializer : BaseApplicationContextInitializer({
  bean<TestSystemInterceptor>(isPrimary = true)
  bean { StoveSerde.jackson.anyByteArraySerde(yourObjectMapper()) } // or any serde that implements StoveSerde<Any, ByteArray>
})

fun SpringApplication.addTestDependencies() {
  this.addInitializers(TestInitializer())
}
```

#### Configuring the SystemUnderTest and Parameters

`addTestDependencies` is an extension that helps us to register our dependencies in the application.

```kotlin  hl_lines="4"
springBoot(
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
TestSystem.validate {
  kafka {
    shouldBeConsumed<AnyEvent> { actual -> }
    shouldBePublished<AnyEvent> { actual -> }
  }
}
```
