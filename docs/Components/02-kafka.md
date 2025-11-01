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

## Testing

### Publishing Messages

You can publish messages to Kafka topics for testing:

```kotlin
TestSystem.validate {
  kafka {
    publish(
      topic = "product-events",
      message = ProductCreated(id = "123", name = "T-Shirt"),
      key = "product-123".some(), // Optional
      headers = mapOf("X-UserEmail" to "user@example.com"), // Optional
      partition = 0 // Optional
    )
  }
}
```

### Asserting Published Messages

Test that your application publishes messages correctly:

```kotlin
TestSystem.validate {
  // Trigger an action in your application
  http {
    postAndExpectBodilessResponse("/products", body = CreateProductRequest(name = "Laptop").some()) { response ->
      response.status shouldBe 200
    }
  }

  // Verify the message was published
  kafka {
    shouldBePublished<ProductCreatedEvent>(atLeastIn = 10.seconds) {
      actual.name == "Laptop" &&
      actual.id != null &&
      metadata.topic == "product-events" &&
      metadata.headers["event-type"] == "PRODUCT_CREATED"
    }
  }
}
```

### Asserting Consumed Messages

Test that your application consumes messages correctly:

```kotlin
TestSystem.validate {
  // Publish a message
  kafka {
    publish(
      topic = "order-events",
      message = OrderCreated(orderId = "456", amount = 100.0)
    )
  }

  // Verify your application consumed and processed it
  kafka {
    shouldBeConsumed<OrderCreated>(atLeastIn = 20.seconds) {
      actual.orderId == "456" &&
      actual.amount == 100.0
    }
  }

  // Verify side effects (e.g., database write)
  couchbase {
    shouldGet<Order>("order:456") { order ->
      order.orderId shouldBe "456"
      order.status shouldBe "CREATED"
    }
  }
}
```

### Testing Failed Messages

Test that your application handles failures correctly:

```kotlin
TestSystem.validate {
  kafka {
    // Publish an invalid message
    publish("user-events", FailingEvent(id = 5L))

    // Verify it failed with the expected reason
    shouldBeFailed<FailingEvent>(atLeastIn = 10.seconds) {
      actual.id == 5L &&
      reason is BusinessException
    }
  }
}
```

### Testing Retry Logic

Test that your application retries failed messages:

```kotlin
TestSystem.validate {
  kafka {
    publish("product-failing", ProductFailingCreated(productId = "789"))
    
    // Verify it was retried 3 times
    shouldBeRetried<ProductFailingCreated>(atLeastIn = 1.minutes, times = 3) {
      actual.productId == "789"
    }

    // Verify it ended up in error topic
    shouldBePublished<ProductFailingCreated>(atLeastIn = 1.minutes) {
      metadata.topic == "product-failing.error"
    }
  }
}
```

### Working with Message Metadata

Access message metadata including headers, topic, partition, offset:

```kotlin
TestSystem.validate {
  kafka {
    shouldBeConsumed<OrderCreated> {
      actual.orderId == "123" &&
      metadata.topic == "order-events" &&
      metadata.headers["correlation-id"] != null &&
      metadata.partition == 0
    }
  }
}
```

### Peeking Messages

Inspect messages without consuming them:

```kotlin
TestSystem.validate {
  kafka {
    // Peek at published messages
    peekPublishedMessages(atLeastIn = 5.seconds, topic = "product-events") { record ->
      record.key == "product-123"
    }

    // Peek at consumed messages
    peekConsumedMessages(atLeastIn = 5.seconds, topic = "order-events") { record ->
      record.offset >= 10L
    }

    // Peek at committed messages
    peekCommittedMessages(topic = "order-events") { record ->
      record.offset == 101L // next offset after 100 messages
    }
  }
}
```

### Admin Operations

Manage Kafka topics and configurations:

```kotlin
TestSystem.validate {
  kafka {
    adminOperations {
      createTopic(NewTopic("test-topic", 1, 1))
      // Other admin operations available here
    }
  }
}
```

### In-Flight Consumer

Create a consumer for advanced testing scenarios:

```kotlin
TestSystem.validate {
  kafka {
    consumer<String, ProductCreated>(
      topic = "product-events",
      readOnly = false, // commit messages
      autoOffsetReset = "earliest",
      autoCreateTopics = true,
      keepConsumingAtLeastFor = 10.seconds
    ) { record ->
      println("Consumed: ${record.value()}")
      // Process the message
    }
  }
}
```

## Complete Example

Here's a complete end-to-end test combining HTTP, Kafka, and database assertions:

```kotlin
test("should create product and publish event") {
  TestSystem.validate {
    val productId = UUID.randomUUID()
    val productName = "Laptop"

    // Mock external service
    wiremock {
      mockGet("/categories/electronics", statusCode = 200, responseBody = Category(id = 1, active = true).some())
    }

    // Make HTTP request
    http {
      postAndExpectBodilessResponse(
        uri = "/products",
        body = ProductCreateRequest(id = productId, name = productName, categoryId = 1).some()
      ) { response ->
        response.status shouldBe 200
      }
    }

    // Verify Kafka message was published
    kafka {
      shouldBePublished<ProductCreatedEvent>(atLeastIn = 10.seconds) {
        actual.id == productId &&
        actual.name == productName &&
        metadata.headers["X-UserEmail"] != null
      }
    }

    // Verify database state
    couchbase {
      shouldGet<Product>("product:$productId") { product ->
        product.id shouldBe productId
        product.name shouldBe productName
      }
    }

    // Verify the event was consumed by another service
    kafka {
      shouldBeConsumed<ProductCreatedEvent>(atLeastIn = 20.seconds) {
        actual.id == productId &&
        actual.name == productName
      }
    }
  }
}
```
