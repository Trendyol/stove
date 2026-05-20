# Tracing

Test failed. Need to know what happened *inside the app*, not just at the test boundary. Stove tracing starts an OTLP receiver for the suite, correlates spans with the current test, and prints the captured span tree alongside the failure.

<div class="stove-tldr" markdown>
<span class="stove-tldr-title">Two steps</span>
1) Apply the <code>stoveTracing</code> Gradle plugin. 2) Call <code>tracing { enableSpanReceiver() }</code> in your <code>Stove().with</code>. For JVM apps launched by Stove, the plugin can attach the OTel Java Agent; for process/container apps, wire your language's OTel SDK to the exposed endpoint. Walk through the full failure loop in <a href="../observability/when-it-fails/">When a test fails</a>.
</div>

## Setup

<a id="gradle-plugin"></a>

### 1. Gradle plugin (recommended)

```kotlin hl_lines="2 6 7 8"
plugins {
    id("com.trendyol.stove.tracing") version "$stoveVersion"
}

stoveTracing {
    serviceName.set("my-service")
    testTaskNames.set(listOf("e2eTest"))
}
```

The plugin:

- attaches the OpenTelemetry Java agent for in-process JVM test tasks
- boots an OTLP gRPC receiver on a free port
- exposes `OTEL_*` values that runners can pass to the AUT
- handles agent download + caching

### 2. Enable span receiver in Stove

```kotlin
Stove().with {
    tracing { enableSpanReceiver() }
    // ... your systems + runner
}.run()
```

For in-process JVM apps, spans from agent-supported libraries such as Spring, JDBC, Kafka, gRPC, HTTP clients, Redis, and MongoDB can show up in the failure report without application code changes. For separate processes or containers, the app must initialize an OTel SDK/exporter and send spans to the receiver.

!!! info "Why Gradle?"
    The `stoveTracing` plugin handles JVM agent attachment and OTLP wiring per test task. Maven can replicate the receiver setup manually, but the agent-attach + per-task port allocation is plugin-only today.

## What you get

<div class="stove-ribbon" markdown="0">
  <div class="stove-ribbon-item">
    <div class="icon">🌲</div>
    <strong>Full call chain on failure</strong>
    <p>Failure reports include the span tree: controller → service → DB → Kafka, with timings per hop.</p>
  </div>
  <div class="stove-ribbon-item">
    <div class="icon">🚀</div>
    <strong>No app changes for JVM agent mode</strong>
    <p>For in-process JVM apps launched by Stove, the OTel agent instruments supported libraries at bytecode level. Non-JVM apps need SDK wiring.</p>
  </div>
  <div class="stove-ribbon-item">
    <div class="icon">🔗</div>
    <strong>W3C propagation</strong>
    <p><code>traceparent</code> headers and Kafka headers carry context when your clients and consumers propagate them.</p>
  </div>
  <div class="stove-ribbon-item">
    <div class="icon">🧪</div>
    <strong>Parallel-test safe</strong>
    <p>Each test gets its own trace ID. Concurrent tests don't blur into one tree.</p>
  </div>
</div>

## Assertions on spans

`tracing { }` exposes a small DSL inside `stove { }`:

```kotlin
stove {
    http { post<OrderResponse>("/orders", body) { /* ... */ } }

    tracing {
        shouldContainSpan("OrderService.place") {
            attributes["order.amount"] shouldBe "99.99"
        }
        shouldNotHaveFailedSpans()
        executionTimeShouldBeLessThan(2.seconds)
    }
}
```

| Assertion | Use for |
|---|---|
| `shouldContainSpan(name) { ... }` | A specific operation ran with expected attributes |
| `shouldNotHaveFailedSpans()` | No span ended in error state |
| `shouldHaveSpanCount(n)` | Span count matches expectation (regression guard) |
| `executionTimeShouldBeLessThan(d)` | Total trace duration under a budget |

## Configuration

`stoveTracing { }` Gradle extension:

| Option | Default | Notes |
|---|---|---|
| `serviceName` | project name | shown in spans + dashboard |
| `testTaskNames` | `["test"]` | tasks the plugin instruments |
| `spanCollectionTimeout` | 5 seconds | how long to wait for late spans after the test ends |
| `maxSpansPerTrace` | 1000 | bounds memory; spans past the cap are dropped |
| `otelAgentVersion` | latest stable | override if you need to pin |

`tracing { }` runtime block options inside `Stove().with`:

| Option | Default | Notes |
|---|---|---|
| `enableSpanReceiver()` | required | starts the OTLP receiver |
| `filterSpans { span -> ... }` | none | drop noise (e.g. health checks) |

## Real-world example

Order placement: HTTP → service → inventory check → DB insert → Kafka publish. When the test fails (no event published), the span tree shows *why*:

```
▾ POST /orders                       42ms
  ├─ OrderController.create          2ms
  ├─ ▾ OrderService.place           35ms
  │    ├─ InventoryClient.fetch     7ms
  │    ├─ ▾ StockGuard.check        1ms
  │    │    └─ returned: INSUFFICIENT
  │    └─ KafkaProducer.send         3ms
  │         topic = order.failed.v1      ← !? expected order.created.v1
  └─ HTTP 201 returned
```

Bug found: inventory shows out-of-stock, app published `order.failed.v1` instead. Walk the entire flow visually in [When a test fails](../observability/when-it-fails.md).

## Polyglot apps (Go, Python, ...)

For non-JVM AUTs, the plugin still boots the OTLP receiver. Process and container runners can pass the endpoint through env vars or CLI args; your language's OTel SDK must read that endpoint and export spans. Those spans can land in the same trace tree as the test when W3C context propagation is wired. See [Polyglot overview](../other-languages/index.md).

## Pairs well with

<div class="grid cards" markdown>

-   :material-monitor-dashboard: **[Dashboard](18-dashboard.md)**. Interactive span tree, attribute search, timeline view.

-   :material-robot-outline: **[MCP](21-mcp.md)**. `stove_trace` exposes the tree to agents.

-   :material-text-box-search-outline: **[Reporting](13-reporting.md)**. Console output combines test entries + span summary.

-   :material-chart-arc: **[When a test fails](../observability/when-it-fails.md)**. The whole loop in one scroll.

</div>

## Troubleshooting

| Symptom | Check |
|---|---|
| No spans in failure report | `stoveTracing` plugin applied? Task name listed in `testTaskNames`? `tracing { enableSpanReceiver() }` in `Stove().with`? |
| Test task hangs at end | Span collection timeout too high; lower `spanCollectionTimeout` |
| Span tree too noisy | Use `filterSpans { ... }` to drop health checks and trivial spans |
| Different agent version needed | Pin via `otelAgentVersion.set("...")` |
| Custom OTel SDK in app conflicts | The plugin's agent takes precedence; remove the in-app SDK or use SDK-only mode (advanced) |
