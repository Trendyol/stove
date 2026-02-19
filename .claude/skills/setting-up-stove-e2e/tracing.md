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
| `disabledInstrumentations` | `[]` | Instrumentations to disable |
| `additionalInstrumentations` | `[]` | Extra instrumentations |
| `customAnnotations` | `[]` | Custom annotation classes to instrument |
| `protocol` | `"grpc"` | OTLP protocol |
| `captureHttpHeaders` | `true` | Capture HTTP headers in spans |

## 4. Trace validation DSL

```kotlin
tracing {
    shouldContainSpan("OrderService.processOrder")
    shouldNotHaveFailedSpans()
    executionTimeShouldBeLessThan(500.milliseconds)
}
```
