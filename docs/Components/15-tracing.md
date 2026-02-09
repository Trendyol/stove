# Tracing

Your end-to-end test just failed. Now what?

You stare at a stack trace that says *"expected message not found within timeout"*. You dig through application logs. You check Kafka topics. You wonder if the HTTP request even reached the controller. Was it a database error? A serialization issue? A Kafka consumer that silently died?

**What if your test failure told you exactly what happened inside your application?**

```
═══════════════════════════════════════════════════════════════════════════════
EXECUTION TRACE (Call Chain)
═══════════════════════════════════════════════════════════════════════════════
✓ POST (377ms)
  ✓ POST /api/product/create (361ms)
    ✓ ProductController.create (141ms)
      ✓ ProductCreator.create (0ms)
      ✓ KafkaProducer.send (137ms)
        ✓ orders.created publish (81ms)
          ✗ orders.created process (82ms)  ← FAILURE POINT
```

That's Stove tracing. When a test fails, you see the **entire call chain** of your application -- every controller method, every database query, every Kafka message, every HTTP call -- with timing and the exact point of failure. It's a unique feature.

## What You Get

When tracing is enabled, every test failure comes with the full story:

```
STOVE EXECUTION REPORT
═══════════════════════════════════════════════════════════════════════════════

TIMELINE
────────
14:45:38.439 ✓ PASSED [HTTP] POST /api/product/create
14:45:38.472 ✗ FAILED [Kafka] shouldBePublished<ProductCreatedEvent>

SYSTEM SNAPSHOTS
────────────────
KAFKA
  Consumed: 0
  Produced: 1
  Failed: 1
    [0] topic: orders.created
        reason: Something went wrong

═══════════════════════════════════════════════════════════════════════════════
EXECUTION TRACE (Call Chain)
═══════════════════════════════════════════════════════════════════════════════
✓ POST (377ms)
  ✓ POST /api/product/create (361ms)
    ✓ ProductController.create (141ms)
      ✓ ProductCreator.create (0ms)
      ✓ KafkaProducer.send (137ms)
        ✓ orders.created publish (81ms)
          ✗ orders.created process (82ms)  ← FAILURE POINT
```

Everything is automatic:

- Traces **start and end** with each test
- W3C `traceparent` headers are **injected into HTTP requests**
- Trace headers are **injected into Kafka messages**
- Trace metadata is **injected into gRPC calls**
- All spans are **correlated back to the originating test**
- Failure reports are **enriched with the execution trace**

When failures include exceptions, you see those too:

```
✗ PaymentGateway.charge [80ms] ⚠ FAILURE POINT
├── Exception: PaymentDeclinedException
│   Message: Card declined
│   at PaymentGateway.charge(PaymentGateway.kt:42)
```

Successful traces render as clean hierarchical trees:

```
✓ OrderController.createOrder [100ms]
├── ✓ OrderService.processOrder [95ms]
│   ├── ✓ UserRepository.findById [10ms]
│   │   └── db.system: postgresql
│   └── ✓ PaymentClient.charge [65ms]
│       └── http.url: https://payment.api/charge

Summary: 4 spans, 0 failures, total: 100ms
```

## Setup

Two steps. That's it.

### Step 1: Enable tracing in your Stove config

```kotlin hl_lines="3-5"
Stove()
    .with {
        tracing {
            enableSpanReceiver()
        }
        // ... your other systems (http, kafka, etc.)
    }
    .run()
```

### Step 2: Attach the OpenTelemetry agent in your build

Copy [StoveTracingConfiguration.kt](https://github.com/Trendyol/stove/blob/main/buildSrc/src/main/kotlin/com/trendyol/stove/gradle/StoveTracingConfiguration.kt) to your project's `buildSrc/src/main/kotlin/` directory, then add to your `build.gradle.kts`:

```kotlin hl_lines="3-5"
import com.trendyol.stove.gradle.configureStoveTracing

configureStoveTracing {
    serviceName = "my-service"
}
```

This handles everything: downloading the OpenTelemetry Java Agent, configuring JVM arguments, attaching the agent to your test tasks, and dynamically assigning ports so parallel test runs don't conflict.

!!! tip "That's all you need"
    Now write your tests as usual. When a test fails, you'll see the execution trace automatically. No code changes to your application required -- the OpenTelemetry agent instruments 100+ libraries (Spring, JDBC, Kafka, gRPC, HTTP clients, Redis, MongoDB, and more) with zero code changes.

### Dependencies

```kotlin
dependencies {
    testImplementation("com.trendyol:stove-tracing:$stoveVersion")
    testImplementation("com.trendyol:stove-extensions-kotest:$stoveVersion")
    // or
    testImplementation("com.trendyol:stove-extensions-junit:$stoveVersion")
}
```

## Zero-Effort Trace Propagation

You don't need to do anything special in your test code. Stove injects trace headers into every interaction automatically:

=== "HTTP"

    ```kotlin
    http {
        get<UserResponse>("/users/123") { user ->
            user.name shouldBe "John"
        }
    }
    ```

=== "Kafka"

    ```kotlin
    kafka {
        publish("orders.created", OrderCreatedEvent(orderId = "123"))
    }
    ```

=== "gRPC"

    ```kotlin
    grpc {
        channel<GreeterServiceStub> {
            sayHello(HelloRequest(name = "World"))
        }
    }
    ```

Every HTTP request gets a `traceparent` header. Every Kafka message gets trace headers. Every gRPC call gets trace metadata. Your application picks these up through the OpenTelemetry agent, and Stove collects the resulting spans -- all without you writing a single line of tracing code.

## Trace Validation DSL

Beyond automatic failure reports, you can actively query and assert on traces using the `tracing { }` DSL. This is useful when you want to verify *how* your application handled a request, not just *that* it did.

```kotlin hl_lines="9-13"
test("order processing should call payment service") {
    stove {
        http {
            post<OrderResponse>("/orders", orderRequest) { response ->
                response.status shouldBe "created"
            }
        }

        tracing {
            shouldContainSpan("OrderService.processOrder")
            shouldContainSpan("PaymentClient.charge")
            shouldNotHaveFailedSpans()
            executionTimeShouldBeLessThan(500.milliseconds)
        }
    }
}
```

### Span Assertions

Verify which operations happened (or didn't) during a test:

```kotlin
tracing {
    shouldContainSpan("UserService.findById")
    shouldContainSpanMatching { it.operationName.contains("Repository") }
    shouldNotContainSpan("AdminService.delete")

    shouldNotHaveFailedSpans()
    shouldHaveFailedSpan("PaymentGateway.charge")

    shouldHaveSpanWithAttribute("http.method", "GET")
    shouldHaveSpanWithAttributeContaining("http.url", "/api/users")
}
```

### Performance Assertions

Assert on execution timing and span counts:

```kotlin
tracing {
    executionTimeShouldBeLessThan(500.milliseconds)
    executionTimeShouldBeGreaterThan(10.milliseconds)

    spanCountShouldBe(10)
    spanCountShouldBeAtLeast(5)
    spanCountShouldBeAtMost(20)
}
```

### Debugging Helpers

When you need to understand what happened during a test, render the trace:

```kotlin
tracing {
    println(renderTree())    // Hierarchical tree view
    println(renderSummary()) // Compact summary

    val failedSpans = getFailedSpans()
    val totalDuration = getTotalDuration()
    val span = findSpanByName("OrderService.process")

    // Wait for spans to arrive before asserting (useful for async flows)
    waitForSpans(expectedCount = 5, timeoutMs = 3000)
}
```

## Real-World Example

Here's a realistic scenario: an HTTP request triggers order processing, which publishes a Kafka event, which is consumed and writes to the database.

```kotlin hl_lines="28-33"
test("should create order and notify downstream services") {
    stove {
        val orderId = UUID.randomUUID().toString()

        // 1. Create order via HTTP
        http {
            post<OrderResponse>("/orders", CreateOrderRequest(orderId, amount = 99.99)) { response ->
                response.status shouldBe "created"
            }
        }

        // 2. Verify Kafka event was published
        kafka {
            shouldBePublished<OrderCreatedEvent>(atLeastIn = 10.seconds) {
                actual.orderId == orderId
            }
        }

        // 3. Verify database state
        postgresql {
            shouldQuery<Order>("SELECT * FROM orders WHERE id = '$orderId'") { orders ->
                orders.size shouldBe 1
                orders.first().status shouldBe "CREATED"
            }
        }

        // 4. Verify the execution flow
        tracing {
            shouldContainSpan("OrderController.create")
            shouldContainSpan("OrderService.processOrder")
            shouldContainSpan("orders.created publish")
            shouldNotHaveFailedSpans()
        }
    }
}
```

If any step fails, the trace tree shows you exactly where and why:

```
✓ POST (250ms)
  ✓ POST /orders (245ms)
    ✓ OrderController.create [120ms]
    ├── ✓ OrderService.processOrder [115ms]
    │   ├── ✓ INSERT INTO orders [15ms]
    │   │   └── db.system: postgresql
    │   └── ✓ KafkaProducer.send [90ms]
    │       └── ✓ orders.created publish [45ms]
    │           └── ✓ orders.created process [40ms]
    │               └── ✓ UPDATE orders SET status='CREATED' [8ms]

Summary: 8 spans, 0 failures, total: 250ms
```

!!! note "Working example"
    For a complete working project with tracing, see the [spring-showcase recipe](https://github.com/Trendyol/stove/tree/main/recipes/kotlin-recipes/spring-showcase).

## Configuration Reference

### Stove Test Config

Configure tracing behavior in your Stove setup:

```kotlin hl_lines="2"
tracing {
    enableSpanReceiver()              // Required: starts the span receiver
    spanCollectionTimeout(10.seconds) // How long to wait for spans (default: 5s)
    maxSpansPerTrace(2000)            // Cap spans per trace (default: 1000)
    spanFilter { span ->              // Filter which spans are collected
        !span.operationName.contains("health-check")
    }
}
```

| Option | Default | Description |
|--------|---------|-------------|
| `enableSpanReceiver(port?)` | Port from `STOVE_TRACING_PORT` env or `4317` | Starts the OTLP gRPC receiver |
| `spanCollectionTimeout` | `5.seconds` | How long to wait for spans when building failure reports |
| `maxSpansPerTrace` | `1000` | Maximum spans stored per trace (prevents memory issues) |
| `spanFilter` | Accept all | Predicate to filter which spans are collected |

### Gradle Build Config

Configure the OpenTelemetry agent via `configureStoveTracing` in your `build.gradle.kts`:

```kotlin hl_lines="2"
configureStoveTracing {
    serviceName = "my-service"
    testTaskNames = listOf("integrationTest") // Only apply to specific tasks
    disabledInstrumentations = listOf("jdbc")  // Exclude noisy instrumentations
}
```

| Option | Default | Description |
|--------|---------|-------------|
| `serviceName` | `"stove-traced-app"` | Service name shown in traces |
| `enabled` | `true` | Toggle tracing on/off |
| `protocol` | `"grpc"` | OTLP protocol (`grpc` or `http/protobuf`) |
| `testTaskNames` | `[]` | Apply only to specific test tasks (empty = all) |
| `otelAgentVersion` | `"2.24.0"` | OpenTelemetry Java Agent version |
| `captureHttpHeaders` | `true` | Include HTTP headers in spans |
| `captureExperimentalTelemetry` | `true` | Enable experimental HTTP telemetry |
| `disabledInstrumentations` | `[]` | Instrumentations to disable (e.g., `jdbc`, `hibernate`) |
| `additionalInstrumentations` | `[]` | Extra instrumentations to enable |
| `customAnnotations` | `[]` | Custom annotation classes to instrument |
| `bspScheduleDelay` | `100` | Batch span processor delay in ms (lower = faster export) |
| `bspMaxBatchSize` | `1` | Batch size for span export (1 = immediate) |

??? note "Manual OTel agent setup"
    If you prefer not to use `configureStoveTracing`, you can configure the agent manually:

    ```kotlin
    // build.gradle.kts
    val otelAgent by configurations.creating { isTransitive = false }

    dependencies {
        otelAgent("io.opentelemetry.javaagent:opentelemetry-javaagent:2.24.0")
    }

    tasks.test {
        doFirst {
            jvmArgs(
                "-javaagent:${otelAgent.singleFile.absolutePath}",
                "-Dotel.traces.exporter=otlp",
                "-Dotel.exporter.otlp.protocol=grpc",
                "-Dotel.exporter.otlp.endpoint=http://localhost:4317",
                "-Dotel.metrics.exporter=none",
                "-Dotel.logs.exporter=none",
                "-Dotel.service.name=my-service",
                "-Dotel.propagators=tracecontext,baggage",
                "-Dotel.traces.sampler=always_on",
                "-Dotel.bsp.schedule.delay=100",
                "-Dotel.bsp.max.export.batch.size=1",
                "-Dotel.instrumentation.grpc.enabled=false"
            )
        }
    }
    ```

## Best Practices

1. **Just enable it** -- tracing is automatic and low-overhead; there's no reason not to use it
2. **Use `tracing { }` sparingly** -- the automatic failure reports cover most debugging needs; use the DSL only when you want to assert on the execution flow
3. **Start with `shouldNotHaveFailedSpans()`** -- the simplest assertion that catches unexpected errors
4. **Filter noise** -- if you see too many spans, use `disabledInstrumentations` to exclude verbose libraries like `jdbc` or `spring-scheduling`
5. **CI just works** -- ports are dynamically assigned, so parallel test runs don't conflict

!!! tip "Works with Reporting"
    Tracing integrates seamlessly with Stove's [Reporting](13-reporting.md) system. When both are enabled, test failures include the execution report *and* the trace tree together, giving you the complete picture.

## Troubleshooting

### No trace in failure reports

1. Ensure `stove-tracing` is in your dependencies
2. Verify `enableSpanReceiver()` is called in your Stove config
3. Verify `configureStoveTracing` is called in your `build.gradle.kts`
4. Look for *"Stove tracing: Attached OTel agent"* in test output

### Too many spans

Use `disabledInstrumentations` to exclude noisy libraries:

```kotlin
configureStoveTracing {
    serviceName = "my-service"
    disabledInstrumentations = listOf("jdbc", "hibernate", "spring-scheduling")
}
```

### Spans missing parent-child relationships

1. Ensure trace context is propagated through async boundaries
2. Check that the OTel agent version is compatible with your framework version
