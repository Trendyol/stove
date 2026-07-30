# Reporting

Every Stove test failure ships with structured execution context. The reporter captures activity from registered systems: HTTP calls, DB ops, Kafka observations, WireMock stubs, and gRPC interactions. When an assertion fails, you get the stack trace plus the sequence of system activity that led to it.

<div class="stove-tldr" markdown>
<span class="stove-tldr-title">In 30 seconds</span>
Add the Kotest or JUnit extension. Failures print a timeline by default (pretty console). Add <code>reporting { }</code> in <code>Stove().with</code> to tune output or use machine-readable JSON. Reporting pairs with <a href="../15-tracing/">Tracing</a> and the <a href="../18-dashboard/">Dashboard</a>; it does not require either one.
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

Default is on, prints to console, and dumps only on failure. Local runs include the complete pretty report. On CI, Stove automatically uses bounded compact output for each failure report.

```kotlin
Stove().with {
    reporting {
        enabled()
        dumpOnFailure()
    }
    // ... your systems
}.run()
```

The CI-aware default recognizes common flags such as `CI`, `GITHUB_ACTIONS`, `GITLAB_CI`, `JENKINS_URL`, `TF_BUILD`, and `BUILDKITE`. Values that are blank, `false`, or `0` do not enable compact mode.

Compact output keeps the complete pass/fail/total counts, then:

- keeps failed entries plus the most recent timeline context, up to 50 entries;
- keeps the most recent 10 items in every rendered collection, including timeline details and snapshots;
- keeps up to 20 entries from each rendered map and up to 10 system snapshots;
- keeps 2,000 characters from each large value, split between its beginning and end;
- stops nested diagnostic traversal after eight levels and detects cyclic values;
- caps the final composed console report at 50,000 characters, including an execution trace appended by a test extension;
- shortens Kafka's inline "Messages so far" assertion dump before the test framework prints it;
- prints an explicit omission notice for every shortened section.

Tune the limits, force compact output everywhere, or force the complete console report:

```kotlin
reporting {
    failureRenderer(
        PrettyConsoleRenderer.compact(
            ConsoleReportLimits(
                maxTimelineEntries = 25,
                maxCollectionItems = 5,
                maxMapEntries = 10,
                maxSnapshots = 5,
                maxValueCharacters = 1_000,
                maxNestingDepth = 6,
                maxOutputCharacters = 25_000
            )
        )
    )

    // Force complete output, including on CI:
    // failureRenderer(PrettyConsoleRenderer)

    // Restore the default explicitly:
    // failureRenderer(PrettyConsoleRenderer.ciAware())
}
```

`JsonReportRenderer` is not shortened. Use it when the complete structured report should be saved as a CI artifact while console output stays compact.

## Renderers

<div class="stove-compare" markdown="0">
  <div>
    <h4>PrettyConsoleRenderer (default)</h4>
    <p>Human-friendly. Color, alignment, system snapshots inline, with compact detail on CI. Built with Mordant.</p>

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
| HTTP | method, path, status, latency, request/response bodies |
| Kafka | producer publishes, consumer reads, topic, partition, offset, payload |
| Databases (SQL + NoSQL) | queries, bind args, rows affected, durations |
| WireMock | stub matches and misses, request body |
| gRPC | method, request, response, status |
| System snapshots | per-system state at failure time (Kafka topics, WireMock unmatched, etc.) |

Snapshots make root-cause analysis faster. A WireMock snapshot, for example, can show that an "unexpected 404" was the app hitting an unmocked path.

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
| Need the complete console report on CI | Set `failureRenderer(PrettyConsoleRenderer)` |
| Need smaller local output too | Set `failureRenderer(PrettyConsoleRenderer.compact())` |
| JSON empty in CI | Use `JsonReportRenderer`; pipe `System.out` to a file or use dashboard JSON export |
