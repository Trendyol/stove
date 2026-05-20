# Kafka

Real Kafka in a container or an existing cluster. Publish from tests, read directly with test consumers, and assert app-produced or app-consumed messages when the Stove Kafka bridge is wired into the AUT.

<a class="open-in-wizard" data-sys="kafka">Open in setup wizard</a>

<!--{wizard:snippet id=sys.kafka parts=gradle,configure,test}-->

<div class="stove-tldr" markdown>
<span class="stove-tldr-title">Two modes</span>
<strong>Standalone</strong> (<code>stove-kafka</code>). Plain Kafka client, works with any framework. <strong>Spring integration</strong> (<code>stove-spring-kafka</code>). Extra assertions for Spring's Kafka listeners. Assertions such as <code>shouldBePublished</code> and <code>shouldBeConsumed</code> rely on the Stove Kafka bridge being wired into your app's producer/consumer path so Stove can observe what the AUT publishes and consumes.
</div>

## Bridge interceptor (required)

Stove can only assert app-side Kafka activity it can observe. For JVM Kafka clients, put the bridge interceptor on your app's producer and consumer interceptor lists. For non-JVM apps, use the language-specific bridge or report equivalent producer/consumer events yourself.

```kotlin
// expose Stove's interceptor class via property
"kafka.interceptorClasses=${cfg.interceptorClass}"
// or hardcode (less flexible):
"kafka.interceptorClasses=com.trendyol.stove.standalone.kafka.intercepting.StoveKafkaBridge"
```

The `kafka.interceptorClasses` *prefix* is whatever your app reads. Mirror your app's property names; Stove does not rewrite application configuration keys for you.

## Standalone setup

```kotlin
Stove().with {
  kafka {
    KafkaSystemOptions(
      serde = StoveSerde.jackson.anyByteArraySerde(),
      configureExposedConfiguration = { cfg ->
        listOf(
          "kafka.bootstrapServers=${cfg.bootstrapServers}",
          "kafka.interceptorClasses=${cfg.interceptorClass}"
        )
      }
    )
  }
}.run()
```

Custom serde:

```kotlin
val mapper = ObjectMapper().apply { /* your app's config */ }

kafka {
  KafkaSystemOptions(
    serde = StoveSerde.jackson.anyByteArraySerde(mapper),
    /* ... */
  )
}
```

## Spring integration

When testing a Spring Boot service with Spring Kafka listeners, use the dedicated starter. Adds listener-aware assertions on top.

```kotlin
dependencies {
  testImplementation("com.trendyol:stove-spring-kafka")
}
```

Register the interceptor bean for the AUT:

=== "Spring Boot 2.x / 3.x"

    ```kotlin
    springBoot(runner = { params ->
      runApplication<MyApp>(*params) {
        addTestDependencies {
          bean<TestSystemKafkaInterceptor<*, *>>(isPrimary = true)
          bean { StoveSerde.jackson.anyByteArraySerde() }
        }
      }
    })
    ```

=== "Spring Boot 4.x"

    ```kotlin
    springBoot(runner = { params ->
      runApplication<MyApp>(*params) {
        addTestDependencies4x {
          registerBean<TestSystemKafkaInterceptor<*, *>>(primary = true)
          registerBean { StoveSerde.jackson.anyByteArraySerde() }
        }
      }
    })
    ```

## Test-friendly settings

Default Kafka client settings are tuned for production throughput, not test feedback. Without test-specific batching, offset, and commit settings, `shouldBePublished` / `shouldBeConsumed` can flake or time out.

```properties
# producer
linger.ms=0
batch.size=1

# consumer
auto.commit.interval.ms=100
auto-offset-reset=earliest
```

Plus broker-level auto-topic-create (handy for parameterized topic names). Wire these via the AUT's Kafka config, not via Stove options.

## Test DSL

### Publishing from the test

```kotlin
stove {
  kafka {
    publish(
      topic = "orders.created",
      message = OrderCreatedEvent(id = "1"),
      key = "1",
      headers = mapOf("X-Correlation-ID" to "abc")
    )
  }
}
```

### Asserting published

```kotlin
stove {
  kafka {
    shouldBePublished<OrderCreatedEvent> {
      actual.id == "1"
    }

    // Negative assertion: nothing matches in N seconds
    shouldNotBePublished<OrderFailedEvent> {
      actual.id == "1"
    }
  }
}
```

How it works under the hood:

<div class="stove-flow" data-scenario="shouldBePublished"></div>

### Asserting consumed (Spring integration)

```kotlin
stove {
  kafka {
    publish("orders.input", incomingOrder)

    shouldBeConsumed<OrderInputEvent> {
      actual.id == incomingOrder.id
    }
  }
}
```

How `shouldBeConsumed` flows across test, broker, app, and bridge:

<div class="stove-flow" data-scenario="shouldBeConsumed"></div>

### Testing retry / failure paths

```kotlin
stove {
  kafka {
    publish("orders.input", invalidOrder)

    // App's listener should requeue / DLT
    shouldBePublished<DLTRecord<OrderInputEvent>> {
      actual.original.id == invalidOrder.id
    }
  }
}
```

### Working with metadata

```kotlin
stove {
  kafka {
    shouldBePublished<OrderCreatedEvent> {
      metadata.topic == "orders.created" &&
        metadata.headers["X-Correlation-ID"] == "abc"
    }
  }
}
```

`metadata` exposes `topic`, `partition`, `offset`, `key`, `headers`, `timestamp`.

### Peek the in-flight stream

```kotlin
stove {
  kafka {
    val all = peek<OrderCreatedEvent>(topic = "orders.created", limit = 50)
    all.map { it.actual.id } shouldContain "1"
  }
}
```

### Admin operations

```kotlin
stove {
  kafka {
    admin().createTopics(NewTopic("audit", 3, 1))
    admin().listTopics().names().get() shouldContain "audit"
  }
}
```

## Complete example

```kotlin hl_lines="7 13 19"
test("order placement publishes events end-to-end") {
  stove {
    val orderId = UUID.randomUUID().toString()

    http {
      postAndExpectBody<OrderResponse>(
        uri = "/orders",
        body = CreateOrderRequest(id = orderId).some()
      ) { it.status shouldBe 201 }
    }

    kafka {
      shouldBePublished<OrderCreatedEvent> {
        actual.id == orderId &&
          actual.status == "CREATED"
      }
    }
  }
}
```

## Provided Kafka cluster

For shared CI clusters: `KafkaSystemOptions.provided(bootstrapServers = ...)`. Add cleanup of test topics. See [Provided Instances · Kafka isolation](11-provided-instances.md#shared-infrastructure-isolation-pattern).

## Pairs well with

- [Tracing](15-tracing.md). Kafka spans appear with topic + partition attributes
- [Bridge](10-bridge.md). Register custom interceptor beans (or replace them per test)
- [Recipes · order flow](../recipes/order-flow.md). Multi-system Kafka assertion
- [Quarkus](../frameworks/quarkus.md). Quarkus needs a classloader tweak (see that page)
