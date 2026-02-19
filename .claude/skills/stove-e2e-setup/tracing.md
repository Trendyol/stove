# Tracing Configuration

Tracing captures the full execution call chain inside your application, shown on test failure.

## 1. Enable span receiver

Add inside `Stove().with { }`:

```kotlin
tracing { enableSpanReceiver() }
```

## 2. Attach the OpenTelemetry agent

### Gradle Plugin (default)

```kotlin
plugins { id("com.trendyol.stove.tracing") version "$stoveVersion" }

stoveTracing {
    serviceName.set("my-service")
    testTaskNames.set(listOf("e2eTest"))
}
```

### buildSrc alternative

Copy `StoveTracingConfiguration.kt` from the Stove repo to `buildSrc/src/main/kotlin/`, then use direct assignment:

```kotlin
import com.trendyol.stove.gradle.stoveTracing

stoveTracing {
    serviceName = "my-service"
    testTaskNames = listOf("e2eTest")
}
```

## 3. Plugin options

| Option | Default | Description |
|---|---|---|
| `serviceName` | `"stove-traced-app"` | Service name in traces |
| `enabled` | `true` | Toggle tracing |
| `testTaskNames` | `[]` | Apply to specific tasks (empty = all) |
| `otelAgentVersion` | `"2.24.0"` | OTel Java Agent version |
| `disabledInstrumentations` | `[]` | Instrumentations to disable (e.g., `jdbc`, `hibernate`) |
| `additionalInstrumentations` | `[]` | Extra instrumentations |
| `customAnnotations` | `[]` | Custom annotation classes to instrument |
| `protocol` | `"grpc"` | OTLP protocol |
| `captureHttpHeaders` | `true` | Capture HTTP headers in spans |
| `captureExperimentalTelemetry` | `true` | Enable experimental HTTP telemetry |
| `bspScheduleDelay` | `100` | Batch span processor delay in ms (lower = faster export) |
| `bspMaxBatchSize` | `1` | Batch size for span export (1 = immediate) |

## 4. Runtime tracing config

Configure inside `Stove().with { }`:

```kotlin
tracing {
    enableSpanReceiver()              // Required
    spanCollectionTimeout(10.seconds) // Wait time for spans (default: 5s)
    maxSpansPerTrace(2000)            // Cap per trace (default: 1000)
    spanFilter { span ->              // Filter collected spans
        !span.operationName.contains("health-check")
    }
}
```

## 5. Trace validation DSL

```kotlin
tracing {
    // Span assertions
    shouldContainSpan("OrderService.processOrder")
    shouldContainSpanMatching { it.operationName.contains("Repository") }
    shouldNotContainSpan("AdminService.delete")
    shouldNotHaveFailedSpans()
    shouldHaveFailedSpan("PaymentGateway.charge")
    shouldHaveSpanWithAttribute("http.method", "GET")
    shouldHaveSpanWithAttributeContaining("http.url", "/api/users")

    // Performance
    executionTimeShouldBeLessThan(500.milliseconds)
    executionTimeShouldBeGreaterThan(10.milliseconds)
    spanCountShouldBe(10)
    spanCountShouldBeAtLeast(5)
    spanCountShouldBeAtMost(20)

    // Debugging helpers
    println(renderTree())     // Hierarchical tree view
    println(renderSummary())  // Compact summary
    val failed = getFailedSpans()
    val duration = getTotalDuration()
    val span = findSpanByName("OrderService.process")

    // Wait for async spans
    waitForSpans(expectedCount = 5, timeoutMs = 3000)
}
```
