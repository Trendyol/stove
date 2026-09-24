# Tracing Configuration

Tracing collects spans exported by the application and correlates them with the test. Add `stove-tracing` and the project's `stove-extensions-kotest` or `stove-extensions-junit` reporting integration. Instrumentation determines which operations are visible; enabling the receiver alone does not instrument an application.

## 1. Enable the receiver

Add inside the existing `Stove().with { ... }` lifecycle:

```kotlin
tracing { enableSpanReceiver() }
```

The receiver accepts OTLP **gRPC**. Its port is selected in order: explicit `enableSpanReceiver(port = ...)`, `STOVE_TRACING_PORT` environment variable, then `4317`.

| Endpoint | Purpose |
|---|---|
| OTLP receiver in the test JVM, default `4317` | Application spans |
| Stove server gRPC ingestion, default `4041` | Dashboard events |
| Stove server HTTP, default `4040` | UI, REST, MCP |
| Kafka observer in the test JVM, separate selected port | Kafka bridge events |

The dashboard ingestion port is not an OTLP endpoint.

## 2. Instrument the application

### JVM application running inside the test JVM

Apply `com.trendyol.stove.tracing` with the version declared in the project's plugin management or version catalog. Configure the actual test task names:

```kotlin
plugins { id("com.trendyol.stove.tracing") }

stoveTracing {
    serviceName.set("my-service")
    testTaskNames.set(listOf("e2eTest"))
}
```

The plugin attaches the OpenTelemetry Java agent to those Gradle `Test` JVMs. It chooses an available port per task and sets `STOVE_TRACING_PORT` and the agent exporter endpoint together. Keep `enableSpanReceiver()` without a hardcoded port so the receiver reads the same value. Verify every custom test task is included and that concurrent forks do not share one receiver port.

### Process, container, or provided application

Instrument the AUT itself using its language SDK, Java agent, or existing telemetry setup. The Gradle plugin only instruments its test JVM. Configure the external app's exporter for OTLP gRPC and the receiver's reachable address; for standard OTel environment configuration on a host process:

```text
OTEL_EXPORTER_OTLP_ENDPOINT=http://localhost:<receiver-port>
OTEL_EXPORTER_OTLP_PROTOCOL=grpc
```

Use plaintext for Stove's local receiver. SDK APIs may take `host:port` instead of a URL (for example, Go's `otlptracegrpc.WithEndpoint`); follow the API actually used by the app. In a container or on another host, replace `localhost` with an address that reaches the test JVM. See [container.md](container.md#step-5-networking-strategies).

Configure W3C Trace Context propagation and preserve context across async work and messaging. Stove's HTTP client sends `traceparent`; the AUT must extract it to keep spans in the test trace. Use a short exporter batch delay, explicit flushing, or Go's `sdktrace.WithSyncer` in tests when export timing causes races. Flush/shut down the exporter gracefully.

## 3. JVM plugin options

Defaults below describe current source; check the project's resolved plugin version.

| Option | Default | Description |
|---|---|---|
| `serviceName` | `"stove-traced-app"` | Service name in traces |
| `enabled` | `true` | Toggle Java agent wiring |
| `testTaskNames` | `[]` | Task names; empty applies to all `Test` tasks |
| `otelAgentVersion` | `"2.24.0"` | OTel Java agent version |
| `disabledInstrumentations` | `[]` | Instrumentations to disable, such as `jdbc` |
| `additionalInstrumentations` | `[]` | Extra instrumentations |
| `customAnnotations` | `[]` | Annotation classes to instrument |
| `protocol` | `"grpc"` | Only `grpc` is supported; other values are rejected |
| `captureHttpHeaders` | `true` | Capture HTTP headers in spans |
| `captureExperimentalTelemetry` | `true` | Enable experimental HTTP telemetry |
| `bspScheduleDelay` | `100` | Batch span processor delay, milliseconds |
| `bspMaxBatchSize` | `1` | Maximum spans per export batch; export is still asynchronous |

## 4. Wait for evidence and assert

Await the business operation and span export before checking absence of failures or span counts. `waitForSpans` returns the collected list even when its timeout expires, so assert the needed count or content afterward. Example for an instrumented order flow:

```kotlin
tracing {
    waitForSpans(expectedCount = 5, timeoutMs = 3000)
    spanCountShouldBeAtLeast(5)
    shouldContainSpan("OrderService.processOrder")
    shouldContainSpanMatching { it.operationName.contains("Repository") }
    shouldNotContainSpan("AdminService.delete")
    shouldNotHaveFailedSpans()
    shouldHaveSpanWithAttribute("http.request.method", "POST")
    shouldHaveSpanWithAttributeContaining("url.path", "/orders")

    executionTimeShouldBeLessThan(500.milliseconds)

    println(renderTree())
    println(renderSummary())
}
```

Choose span names and counts from the instrumentation actually installed; method-level spans require that instrumentation. Attribute names also vary: recent OTel uses `http.request.method`, `url.path`, or `url.full`, while older agents may emit `http.method` / `http.url`.

For a test that expects a failure, use `shouldHaveFailedSpan("PaymentGateway.charge")` instead of the no-failures assertion. Other helpers include `spanCountShouldBe`, `spanCountShouldBeAtMost`, `executionTimeShouldBeGreaterThan`, `getFailedSpans`, `getTotalDuration`, and `findSpanByName`.

`TracingOptions` declares `spanCollectionTimeout`, `spanFilter`, and `maxSpansPerTrace`, but current `TracingSystem` does not apply those values to its collector. Do not rely on them as effective wait, filtering, or retention controls without checking the resolved source. Use the explicit wait above for export timing.

## Diagnose missing spans

Check the receiver startup log and port first, then the AUT's exporter protocol/address, installed instrumentation, sampling, and propagation. A receiver bind failure is logged and tests can continue without spans. If spans arrive under another trace, inspect `getAllTraceVisualizations()` and trace headers rather than repeatedly increasing timeouts.

Source: `lib/stove-tracing/`, `plugins/stove-tracing-gradle-plugin/`.
