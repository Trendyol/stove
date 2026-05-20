# Stove Dashboard in 0.23.0: See Your E2E Runs Live

End-to-end tests usually answer one question first: pass or fail. When they fail, the useful evidence is spread across the assertion output, application logs, traces, broker tools, and database clients.

As of **Stove 0.23.0**, **Stove Dashboard** and the **`stove` CLI** give those registered Stove systems a local place to stream their evidence while the suite is running.

Dashboard can show:

- a real-time timeline of test actions
- distributed trace trees linked to tests, when tracing is enabled
- state snapshots from registered systems
- persistent run history in SQLite

It does not replace assertions, logs, or tracing setup. It gives you one place to correlate them during local debugging.

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

### 2) Add Dashboard, and tracing if you want span trees

```kotlin
// build.gradle.kts
plugins {
  id("com.trendyol.stove.tracing") version "$stoveVersion"
}

dependencies {
  testImplementation(platform("com.trendyol:stove-bom:$version"))
  testImplementation("com.trendyol:stove-dashboard")
  testImplementation("com.trendyol:stove-tracing")
}

stoveTracing {
  serviceName.set("product-api")
}
```

The dashboard dependency streams Stove timeline entries and snapshots to the CLI. The trace view also needs `stove-tracing` plus the tracing Gradle plugin for in-process JVM apps. Process and container apps can appear in the same dashboard, but they must export OpenTelemetry spans to the receiver themselves.

### 3) Register Dashboard in Stove config

```kotlin
Stove()
  .with {
    dashboard { DashboardSystemOptions(appName = "product-api") }
    tracing { enableSpanReceiver() } // recommended for trace view
    // other systems: http, kafka, postgresql, wiremock...
    // runner goes last: springBoot, ktor, processApp, containerApp, ...
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

This gives you both sides of the picture: test-level evidence from Stove systems and application-level execution details from tracing.

## Daily Workflow That Works Well

Use Dashboard as a local companion while iterating:

1. Start CLI once: `stove`
2. Keep it running in a separate terminal
3. Run focused tests repeatedly (class or test-level)
4. Inspect changes immediately in Timeline/Trace views
5. Use Reporting + Tracing in CI; use Dashboard primarily for local debugging speed

Dashboard is fault-tolerant by design. If the CLI is not running, tests continue normally and Dashboard emission disables itself for the rest of the suite instead of breaking test execution.

## Minimal End-to-End Example

```kotlin
class StoveConfig : AbstractProjectConfig() {
  override suspend fun beforeProject() =
    Stove()
      .with {
        dashboard { DashboardSystemOptions(appName = "spring-example") }
        tracing { enableSpanReceiver() }
        // other systems...
        // AUT runner goes last
        springBoot(
          runner = { params -> com.example.run(params) },
          withParameters = listOf("server.port=8080")
        )
      }.run()

  override suspend fun afterProject() = Stove.stop()
}
```

You keep writing the same Stove tests. The dashboard system captures entries from registered Stove systems, spans when tracing is enabled, and snapshots when systems provide them.

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
