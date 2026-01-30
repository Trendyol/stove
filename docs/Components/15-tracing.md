# Distributed Tracing

Stove provides built-in distributed tracing capabilities to capture detailed execution traces of code paths within your application under test.

## Overview

When tracing is enabled, Stove **automatically**:
- Starts and ends traces for each test (via Kotest/JUnit extensions)
- Injects W3C `traceparent` headers into HTTP requests
- Injects trace headers into Kafka messages
- Injects trace metadata into gRPC calls
- Correlates all spans back to the originating test
- **Displays execution traces on test failures** showing exactly what went wrong

This allows you to see exactly what happened inside your application during a test, making debugging failures much easier.

## Installation

The tracing module is included automatically when using the Kotest or JUnit extensions:

```kotlin
// build.gradle.kts
dependencies {
    testImplementation("com.trendyol:stove-tracing:$stoveVersion")
    testImplementation("com.trendyol:stove-extensions-kotest:$stoveVersion")
    // or
    testImplementation("com.trendyol:stove-extensions-junit:$stoveVersion")
}
```

## Quick Start

Tracing is **automatic** - no manual setup required! Just write your tests:

```kotlin
test("order creation flow") {
    stove {
        // Trace headers are automatically injected
        http {
            post<OrderResponse>("/orders", orderRequest) { response ->
                response.status shouldBe "created"
            }
        }

        kafka {
            shouldBeConsumed<OrderCreatedEvent> { event ->
                event.orderId shouldBe response.orderId
            }
        }
    }
}
```

## Capturing Application Execution Flow

To capture the **internal** execution flow of your application under test (controller methods, database queries, Kafka operations, etc.), you need:

1. **Stove's span receiver** - Collects spans exported from the app
2. **OpenTelemetry Java Agent** - Auto-instruments your application

### Step 1: Enable Span Receiver in Stove

In your Stove test configuration:

```kotlin
Stove(...)
    .with {
        tracing {
            enableSpanReceiver()  // Port is auto-configured from STOVE_TRACING_PORT env var
        }
        // ... other systems
    }
```

Note: The service name is automatically extracted from incoming spans (set by the OTel agent via `-Dotel.service.name`).

### Step 2: Configure OpenTelemetry Java Agent

The easiest way to configure the OTel agent is to copy Stove's configuration helper to your project.

#### Option A: Copy Configuration File (Recommended)

1. **Copy the configuration file** from [StoveTracingConfiguration.kt](https://github.com/Trendyol/stove/blob/main/buildSrc/src/main/kotlin/com/trendyol/stove/gradle/StoveTracingConfiguration.kt) to your project's `buildSrc/src/main/kotlin/` directory.

2. **Use it in your build.gradle.kts:**

```kotlin
import com.trendyol.stove.gradle.configureStoveTracing

configureStoveTracing {
    serviceName = "my-service"
}
```

That's it! The configuration handles:
- Downloading the OpenTelemetry Java Agent
- Configuring all necessary JVM arguments
- Attaching the agent to test tasks

#### Option B: Manual Configuration

If you prefer manual setup:

```kotlin
// build.gradle.kts
val otelAgent by configurations.creating {
    isTransitive = false
}

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
            "-Dotel.instrumentation.grpc.enabled=false"  // Avoid instrumenting the exporter
        )
    }
}
```

### Configuration Options

When using `configureStoveTracing`:

| Option | Default | Description |
|--------|---------|-------------|
| `serviceName` | `"stove-traced-app"` | Service name shown in traces |
| `enabled` | `true` | Toggle tracing on/off |
| `endpoint` | `"http://localhost:4317"` | OTLP endpoint |
| `protocol` | `"grpc"` | OTLP protocol (grpc or http/protobuf) |
| `testTaskNames` | `[]` | Apply only to specific tasks (empty = all) |
| `otelAgentVersion` | `"2.24.0"` | OTel agent version |
| `captureHttpHeaders` | `true` | Capture HTTP headers in spans |
| `disabledInstrumentations` | `[]` | Disable specific instrumentations |

#### Apply to Specific Test Tasks

```kotlin
configureStoveTracing {
    serviceName = "my-service"
    testTaskNames = listOf("integrationTest")  // Only apply to integrationTest
}
```

#### Disable Specific Instrumentations

```kotlin
configureStoveTracing {
    serviceName = "my-service"
    disabledInstrumentations = listOf("jdbc", "hibernate")
}
```

### What Gets Instrumented Automatically

The OTel agent auto-instruments 100+ libraries with **zero code changes**:
- **Spring MVC/WebFlux** - All controller methods
- **JDBC/Hibernate/R2DBC** - All database queries
- **Kafka** - Producer and consumer spans
- **HTTP clients** - OkHttp, Apache HttpClient, WebClient
- **gRPC** - Client and server calls
- **Redis, MongoDB, Elasticsearch** - All operations

## Test Failure Reports

When a test fails, Stove automatically includes the execution trace in the failure report:

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

The trace clearly shows:
- ✓ Successful operations
- ✗ Failed operations and where they occurred
- Timing for each operation

## Tracing DSL

To validate traces or access trace context programmatically, use the `tracing { }` DSL:

```kotlin
test("validate trace spans") {
    stove {
        http {
            get<UserResponse>("/users/123") { ... }
        }

        // Access trace context and validation methods directly
        tracing {
            // Context properties
            println("TraceId: $traceId")
            println("TestId: $testId")

            // Validation methods available directly
            shouldContainSpan("UserController.getUser")
            shouldNotHaveFailedSpans()
            spanCountShouldBeAtLeast(2)

            // Render trace tree
            println(renderTree())
        }
    }
}
```

## Trace Validation Methods

All validation methods are available directly inside the `tracing { }` block:

```kotlin
tracing {
    // Span existence
    shouldContainSpan("UserService.findById")
    shouldContainSpanMatching { it.operationName.contains("Repository") }
    shouldNotContainSpan("AdminService.delete")

    // Failure assertions
    shouldNotHaveFailedSpans()
    shouldHaveFailedSpan("PaymentGateway.charge")

    // Count assertions
    spanCountShouldBe(10)
    spanCountShouldBeAtLeast(5)
    spanCountShouldBeAtMost(20)

    // Timing assertions
    executionTimeShouldBeLessThan(500.milliseconds)
    executionTimeShouldBeGreaterThan(10.milliseconds)

    // Attribute assertions
    shouldHaveSpanWithAttribute("http.method", "GET")
    shouldHaveSpanWithAttributeContaining("http.url", "/api/users")

    // Get data for custom assertions
    val failedSpans = getFailedSpans()
    val totalDuration = getTotalDuration()
    val span = findSpanByName("OrderService.process")

    // Render for debugging
    println(renderTree())
    println(renderSummary())
}
```

## Recording Custom Spans

You can record custom spans for testing:

```kotlin
tracing {
    collector.record(
        SpanInfo(
            traceId = traceId,
            spanId = TraceContext.generateSpanId(),
            parentSpanId = rootSpanId,
            operationName = "CustomOperation",
            serviceName = "my-service",
            startTimeNanos = System.nanoTime(),
            endTimeNanos = System.nanoTime() + 1_000_000,
            status = SpanStatus.OK
        )
    )

    shouldContainSpan("CustomOperation")
}
```

## Trace Tree Rendering

Traces are rendered as hierarchical trees showing the execution flow:

```
Execution Trace for test: order-creation-test
══════════════════════════════════════════════════════════════════════════════

✓ OrderController.createOrder [100ms]
├── ✓ OrderService.processOrder [95ms]
│   ├── ✓ UserRepository.findById [10ms]
│   │   └── db.system: postgresql
│   └── ✓ PaymentClient.charge [65ms]
│       └── http.url: https://payment.api/charge

══════════════════════════════════════════════════════════════════════════════
Summary: 4 spans, 0 failures, total: 100ms
```

When failures occur, they're clearly marked:

```
✗ PaymentGateway.charge [80ms] ⚠ FAILURE POINT
├── Exception: PaymentDeclinedException
│   Message: Card declined
│   at PaymentGateway.charge(PaymentGateway.kt:42)
```

## Automatic Header Injection

When a trace is active, Stove automatically injects trace headers:

### HTTP Requests
```kotlin
// Headers automatically injected:
// - traceparent: 00-{traceId}-{spanId}-01
// - X-Stove-Test-Id: {testId}

http {
    get<UserResponse>("/users/123") { user ->
        user.name shouldBe "John"
    }
}
```

### Kafka Messages
```kotlin
kafka {
    // Headers automatically injected into the message
    publish("orders.created", OrderCreatedEvent(orderId = "123"))
}
```

### gRPC Calls
```kotlin
grpc {
    channel<GreeterServiceStub> {
        // Metadata automatically includes trace context
        sayHello(HelloRequest(name = "World"))
    }
}
```

## Trace Context Properties

Inside the `tracing { }` block, you have access to:

| Property | Description |
|----------|-------------|
| `traceId` | The W3C trace ID (32 hex characters) |
| `rootSpanId` | The root span ID (16 hex characters) |
| `testId` | The test identifier (e.g., `"MySpec::my test"`) |
| `ctx` | The full `TraceContext` object |
| `collector` | The span collector for recording custom spans |

## Best Practices

1. **Tracing is automatic** - No need to manually start/end traces
2. **Use `tracing { }` for validation** - Only when you need to assert on spans
3. **Check for failures first** - `shouldNotHaveFailedSpans()` catches unexpected errors
4. **Use `renderTree()` for debugging** - Visualize the execution flow
5. **Filter noise** - Use `disabledInstrumentations` to exclude noisy libraries

## Troubleshooting

### Spans not appearing in failure report

1. Ensure the `stove-tracing` dependency is included
2. Verify `enableSpanReceiver(port = 4317)` is configured
3. Check that the OTel agent is attached (look for "Stove tracing: Attached OTel agent" in logs)
4. Verify the endpoint in `configureStoveTracing` matches the `enableSpanReceiver` port

### OTel agent not attaching

1. Ensure `configureStoveTracing` is called in your build.gradle.kts
2. Check that the `otelAgent` dependency is being downloaded
3. Look for agent attachment messages in test output

### Too many spans

1. Use `disabledInstrumentations` to exclude noisy libraries:
   ```kotlin
   configureStoveTracing {
       serviceName = "my-service"
       disabledInstrumentations = listOf("jdbc", "hibernate", "spring-scheduling")
   }
   ```

### Missing parent-child relationships

1. Ensure trace context is propagated through async boundaries
2. Check that all services use the same trace ID from the `traceparent` header
3. Verify the OTel agent version is compatible with your framework version
