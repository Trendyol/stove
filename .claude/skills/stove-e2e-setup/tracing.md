# Tracing Configuration

Tracing gives you the full execution call chain inside your application when a test fails.

## 1. Enable span receiver in Stove config

Add inside `Stove().with { }`:

```kotlin
tracing {
    enableSpanReceiver()
}
```

## 2. Attach the OpenTelemetry agent

### Gradle Plugin (recommended)

```kotlin
plugins {
    id("com.trendyol.stove.tracing") version "$stoveVersion"
}

stoveTracing {
    serviceName.set("my-service")
    testTaskNames.set(listOf("e2eTest"))
}
```

### buildSrc alternative

Copy `StoveTracingConfiguration.kt` from the Stove repo to `buildSrc/src/main/kotlin/`, then use direct assignment syntax:

```kotlin
import com.trendyol.stove.gradle.stoveTracing

stoveTracing {
    serviceName = "my-service"
    testTaskNames = listOf("e2eTest")
    otelAgentVersion = libs.opentelemetry.instrumentation.annotations.get().version!!
}
```

## 3. Plugin options

| Option | Type | Default | Description |
|---|---|---|---|
| `serviceName` | `Property<String>` | `"stove-traced-app"` | Service name in traces |
| `enabled` | `Property<Boolean>` | `true` | Toggle tracing on/off |
| `testTaskNames` | `ListProperty<String>` | `[]` | Apply to specific test tasks (empty = all) |
| `otelAgentVersion` | `Property<String>` | `"2.24.0"` | OpenTelemetry Java Agent version |
| `disabledInstrumentations` | `ListProperty<String>` | `[]` | Instrumentations to disable |
| `additionalInstrumentations` | `ListProperty<String>` | `[]` | Extra instrumentations to enable |
| `customAnnotations` | `ListProperty<String>` | `[]` | Custom annotation classes to instrument |
| `protocol` | `Property<String>` | `"grpc"` | OTLP protocol |
| `bspScheduleDelay` | `Property<Int>` | (default) | Batch span processor schedule delay (ms) |
| `bspMaxBatchSize` | `Property<Int>` | (default) | Max batch size (1 = immediate export) |
| `captureHttpHeaders` | `Property<Boolean>` | `true` | Capture HTTP headers in spans |
| `captureExperimentalTelemetry` | `Property<Boolean>` | `true` | Enable experimental HTTP telemetry |

## 4. Trace validation DSL

Assert on traces in tests:

```kotlin
tracing {
    shouldContainSpan("OrderService.processOrder")
    shouldContainSpan("PaymentClient.charge")
    shouldNotHaveFailedSpans()
    executionTimeShouldBeLessThan(500.milliseconds)
}
```
