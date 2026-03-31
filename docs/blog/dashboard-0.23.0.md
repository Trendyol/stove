# Stove Dashboard in 0.23.0: See Your E2E Runs Live

End-to-end tests usually answer one question: pass or fail. When they fail, you jump between logs, traces, and broker/db tools to understand what happened.

As of **Stove 0.23.0**, you can use **Stove Dashboard** and the **`stove` CLI** to watch test execution in a local dashboard while tests are running.

Dashboard gives you:

- a real-time timeline of test actions
- distributed trace trees linked to tests
- state snapshots across systems
- persistent run history in SQLite

Instead of treating failures as black boxes, you can inspect the full story in one place.

<p align="center">
  <video width="900" controls>
    <source src="../../assets/stove_dashboard.webm" type="video/webm" />
    Your browser does not support embedded videos. You can download the Stove Dashboard demo from
    ../../assets/stove_dashboard.webm.
  </video>
</p>

## Quick Setup (5 Minutes)

### 1) Install and start the CLI

```bash
brew install Trendyol/trendyol-tap/stove
stove
```

By default, the dashboard is at `http://localhost:4040` and gRPC receiver is at `localhost:4041`.

### 2) Add the test dependency

```kotlin
dependencies {
  testImplementation(platform("com.trendyol:stove-bom:$version"))
  testImplementation("com.trendyol:stove-dashboard")
  testImplementation("com.trendyol:stove-tracing")
}
```

### 3) Register Dashboard in Stove config

```kotlin
Stove()
  .with {
    dashboard { DashboardSystemOptions(appName = "product-api") }
    tracing { enableSpanReceiver() } // recommended for trace view
    // other systems: http, kafka, postgresql, wiremock...
  }.run()
```

### 4) Run tests and open dashboard

```bash
./gradlew test
```

Open `http://localhost:4040` and inspect runs as they stream in.

## How to Use Dashboard During Debugging

When a test fails (or behaves unexpectedly), this sequence is usually the fastest:

1. **Timeline:** find the first failed action and inspect its input/output and expected/actual values.
2. **Trace:** jump to the span tree to locate the failure point inside app call flow.
3. **Snapshots:** confirm system state around the failure boundary.
4. **Kafka Explorer:** verify published/consumed message counts and payloads.

This gives you both sides of the picture: test-level assertions and application-level execution details.

## Daily Workflow That Works Well

Use Dashboard as a local companion while iterating:

1. Start CLI once: `stove`
2. Keep it running in a separate terminal
3. Run focused tests repeatedly (class or test-level)
4. Inspect changes immediately in Timeline/Trace views
5. Use Reporting + Tracing in CI; use Dashboard primarily for local debugging speed

Dashboard is fault-tolerant by design. If CLI is not running, tests continue normally and Dashboard emission auto-degrades without breaking test execution.

## Minimal End-to-End Example

```kotlin
class StoveConfig : AbstractProjectConfig() {
  override suspend fun beforeProject() =
    Stove()
      .with {
        dashboard { DashboardSystemOptions(appName = "spring-example") }
        tracing { enableSpanReceiver() }
        // other systems...
      }.run()

  override suspend fun afterProject() = Stove.stop()
}
```

You keep writing tests exactly as before; Dashboard captures entries/spans/snapshots automatically.

## Troubleshooting Quick Checks

- **UI stuck at waiting state:** ensure `stove` is running before tests.
- **No events appear:** verify `stove-dashboard` dependency and `dashboard {}` registration.
- **Port mismatch:** align `DashboardSystemOptions(cliPort = ...)` with CLI `--grpc-port`.
- **Too much historical data:** run `stove --clear`.

## Links

- [Dashboard component docs](../Components/18-dashboard.md)
- [0.23.0 release notes](../release-notes/0.23.0.md)
- [Tracing component docs](../Components/15-tracing.md)
- [Getting started](../getting-started.md)
