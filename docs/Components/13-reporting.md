# Reporting

Every Stove test failure ships with a structured execution context. Reporter captures HTTP calls, DB ops, Kafka publishes, WireMock stubs, gRPC interactions. When an assertion goes red, you see what happened before, not a bare stack trace.

<div class="stove-tldr" markdown>
<span class="stove-tldr-title">In 30 seconds</span>
Add the Kotest or JUnit extension. Failures print a timeline by default (pretty console). Add <code>reporting { }</code> in <code>Stove().with</code> to control output or capture machine-readable JSON. Pairs naturally with <a href="../15-tracing/">Tracing</a> and the <a href="../18-dashboard/">Dashboard</a>.
</div>

## Setup

=== "Kotest"

    ```kotlin
    dependencies {
      testImplementation("com.trendyol:stove-extensions-kotest")
    }

    class StoveConfig : AbstractProjectConfig() {
      override val extensions: List<Extension> = listOf(StoveKotestExtension())
      // ...
    }
    ```

=== "JUnit"

    ```kotlin
    dependencies {
      testImplementation("com.trendyol:stove-extensions-junit")
    }

    @ExtendWith(StoveJUnitExtension::class)
    abstract class BaseE2ETest { /* ... */ }
    ```

The extension registers an `AfterTestListener` that intercepts failures and prints the report.

## Configure

Default is on, prints to console, dumps only on failure. Override inside `Stove().with { }`:

```kotlin
Stove().with {
    reporting {
        ReportingOptions(
            enabled = true,
            dumpOnFailure = true,                       // false = dump every test
            failureRenderer = PrettyConsoleRenderer(),  // or JsonReportRenderer()
        )
    }
    // ... your systems
}.run()
```

## Renderers

<div class="stove-compare" markdown="0">
  <div>
    <h4>PrettyConsoleRenderer (default)</h4>
    <p>Human-friendly. Color, alignment, system snapshots inline. Built with Mordant.</p>

```
─── Stove Report ────────────
▶ http    POST /orders   201
▶ kafka   shouldBePublished
        topic=order.created.v1
        timeout=10s   (timed out)
─────────────────────────────
```

  </div>
  <div>
    <h4>JsonReportRenderer</h4>
    <p>Machine-readable. Pipe into CI artifacts, MCP, or your own analyzer.</p>

```json
{
  "test": "OrderE2ETest.create",
  "entries": [
    { "kind": "http", "method": "POST",
      "path": "/orders", "status": 201 },
    { "kind": "kafka", "op": "shouldBePublished",
      "topic": "order.created.v1",
      "status": "timeout" }
  ]
}
```

  </div>
</div>

## What gets reported

| Surface | Captured |
|---|---|
| HTTP | method, path, status, latency, request/response bodies (truncated) |
| Kafka | producer publishes, consumer reads, topic, partition, offset, payload (truncated) |
| Databases (SQL + NoSQL) | queries, bind args, rows affected, durations |
| WireMock | stub matches and misses, request body |
| gRPC | method, request, response, status |
| System snapshots | per-system state at failure time (Kafka topics, WireMock unmatched, etc.) |

Snapshots make root-cause analysis painless. WireMock snapshot alone tells you when an "unexpected 404" was actually your test hitting an unmocked path.

## Pairs well with

<div class="grid cards" markdown>

-   :material-chart-timeline-variant: **[Tracing](15-tracing.md)**. Reporter plus OTel = call chain inside your app, not just the test view.

-   :material-monitor-dashboard: **[Dashboard](18-dashboard.md)**. Same data, browseable in a local web UI; persists across sessions.

-   :material-robot-outline: **[MCP](21-mcp.md)**. Agents fetch the same evidence in token-efficient slices.

-   :material-chart-arc: **[When a test fails](../observability/when-it-fails.md)**. The full failure flow as a scroll story.

</div>

## Troubleshooting

| Symptom | Check |
|---|---|
| No report on failure | Extension registered? `StoveKotestExtension()` in `extensions` (Kotest) or `@ExtendWith(StoveJUnitExtension::class)` (JUnit) |
| Report missing system entries | System registered before the runner block in `Stove().with { }` |
| Empty Kafka snapshot | Interceptor bean registered? See [Kafka pitfalls](02-kafka.md) |
| JSON empty in CI | Use `JsonReportRenderer()`; pipe `System.out` to a file or use dashboard JSON export |
