# Polyglot

Stove's testing model is not limited to JVM applications. If an application can be started as a process, a container, or
an already-running endpoint, Stove can drive it through external systems such as HTTP, gRPC, databases, Kafka, WireMock,
and tracing. Go, Python, Rust, Node, and .NET follow the same runtime pattern; the language-specific work is reading
configuration and exporting telemetry.

<div class="stove-tldr" markdown>
<span class="stove-tldr-title">Same DSL, different runner</span>
<code>stove { http { } postgresql { } kafka { } tracing { } }</code> keeps the same shape for registered systems. The application-under-test (AUT) runner changes to <code>processApp()</code>, <code>goApp()</code>, <code>containerApp()</code>, or <code>providedApplication()</code>. Process and container runners map system configuration into env vars or CLI args before startup. <code>providedApplication()</code> targets an app that is already running and externally configured; Stove checks readiness and runs assertions against configured endpoints and provided infrastructure. Bridge is unavailable because the app runs outside the test JVM.
</div>

## Two ways to run the app

<div class="stove-compare" markdown="0">
  <div>
    <h4>🏃 <code>stove-process</code></h4>
    <p>Run a host binary (<code>goApp()</code> / <code>processApp()</code>). Fastest inner loop when local runtime drift is acceptable.</p>
    <ul>
      <li>Quick to iterate</li>
      <li>Zero infra beyond your compiler</li>
      <li>Approximate production parity (host runtime)</li>
      <li>Best for dev + smoke tests</li>
    </ul>
  </div>
  <div>
    <h4>🐳 <code>stove-container</code></h4>
    <p>Run any Docker image (<code>containerApp()</code>). Stronger parity with the artifact you ship.</p>
    <ul>
      <li>Production-image parity</li>
      <li>Image build per run</li>
      <li>Best for pre-merge + release validation</li>
      <li>Language-agnostic</li>
    </ul>
  </div>
</div>

A common pattern: `e2eTest` (process) for dev, `e2eTest-container` for CI. Same Kotlin tests, same `StoveConfig`, branched on `-Dgo.aut.mode=process|container`.

## How it works

```mermaid
graph LR
    S[Stove Test<br/>Kotlin] -->|starts containers| PG[(PostgreSQL)]
    S -->|starts containers| K[(Kafka)]
    S -->|starts OTLP receiver| T[Tracing]
    S -->|process or container| APP[Your App<br/>Go · Python · Rust · ...]
    APP -->|connects| PG
    APP -->|connects| K
    APP -->|exports spans| T
    S -->|HTTP / gRPC asserts| APP
    S -->|DB asserts| PG
    S -->|trace asserts| T
```

For process and container runners, Stove launches infrastructure, maps connection details into environment variables or
CLI arguments, waits for readiness, and runs the standard DSL against the AUT. With `providedApplication()`, Stove does
not start the app or inject configuration; the app must already be configured to reach the same provided infrastructure.

## Language requirements

Any language can work if the AUT can:

1. **Read env vars** (or CLI args) for DB URLs, ports, credentials
2. **Expose readiness**. HTTP `/health` (preferred), TCP probe, custom probe, fixed delay
3. **Handle SIGTERM**. Needed for clean teardown and, for Go, integration coverage flush

## Languages we walk through

<div class="stove-catalog">
  <div class="stove-sys-card">
    <div class="stove-sys-card-head"><strong>Go</strong><span class="stove-sys-card-badge">First-class</span></div>
    <p class="stove-sys-card-desc">HTTP · Postgres · Kafka (sarama / franz-go / segmentio) · OTel · Dashboard · MCP · integration coverage.</p>
    <div class="stove-sys-card-actions">
      <a href="go/">Overview</a>
      <a href="go-process/">Process mode</a>
      <a href="go-container/">Container mode</a>
    </div>
  </div>
</div>

Python, Rust, Node, and .NET follow the same shape. Pick `processApp()` or `containerApp()`, map the configuration your
app needs, and use that language's OpenTelemetry SDK if you want trace assertions. Open an issue if you want a dedicated
walkthrough.

## Process vs container at a glance

| Concern | `stove-process` | `stove-container` |
|---|---|---|
| Starter | `goApp()` / `processApp()` | `containerApp()` |
| AUT artifact | Host binary | Docker image |
| Iteration speed | Fast (compile + run) | Slower (image build) |
| Production parity | Approximate | Exact |
| CI fit | Smoke / inner loop | Pre-merge / release |
| Networking | Loopback | Host network *or* port binding |
| Filesystem isolation | Host filesystem | Container layer + bind mounts |
| Common pitfalls | Runtime drift hidden | Network mode + port wiring |

## vs JVM apps

| Concern | JVM app | Non-JVM app |
|---|---|---|
| AUT startup | `springBoot()`, `ktor()`, ... | `goApp()` / `processApp()` / `containerApp()` |
| Config | JVM properties | `envMapper` / `argsMapper` |
| Infra | Same (`postgresql { }`, `kafka { }`, ...) | Same |
| Test DSL | Same | Same |
| Tracing | OTel Java Agent (auto) | OTel SDK for your language |
| Dashboard / MCP | Same | Same |
| Bridge `using<T> { }` | ✓ | ✗ (separate process / container) |

## The pattern

### 1. Wire the AUT

```kotlin
// Process mode
goApp(
  target = ProcessTarget.Server(port = 8090, portEnvVar = "APP_PORT"),
  envProvider = envMapper { /* ... */ }
)

// Container mode
containerApp(
  image = "my-app:local",
  target = ContainerTarget.Server(hostPort = 8090, internalPort = 8090, portEnvVar = "APP_PORT"),
  envProvider = envMapper { /* ... */ },
  configureContainer = { withNetworkMode("host") }
)
```

### 2. Instrument with OpenTelemetry

Enable the **`stoveTracing` Gradle plugin**. It boots the OTLP gRPC receiver, picks a free port, and exposes it to your tests + AUT via env vars:

```kotlin hl_lines="2 5 6 7"
plugins {
    id("com.trendyol.stove.tracing") version "$stoveVersion"
}

stoveTracing {
    serviceName.set("my-service")
    testTaskNames.set(listOf("e2eTest"))   // and "e2eTest-container", etc.
}
```

Then instrument your app with your language's OTel SDK. Stove correlates spans back to the test via W3C `traceparent`.
JVM apps can use the Java agent; non-JVM apps need explicit SDK instrumentation and must read the OTLP endpoint from the
standard env vars.

### 3. Write tests with the standard DSL

`http { }`, `postgresql { }`, `kafka { }`, and `tracing { }` use the same Stove test DSL as JVM tests, as long as the
corresponding systems are registered and the AUT is configured to talk to them. Dashboard and MCP are observability and
access surfaces; they record or expose run evidence but are not assertion blocks.

## What you can't do

- :x: **No `bridge()` / `using<T> { }`**. Different process / container.

External-surface assertions still work: HTTP/gRPC, database queries, WireMock, and tracing. Kafka assertions such as
`shouldBePublished` and `shouldBeConsumed` require a Stove Kafka bridge or equivalent client instrumentation in the
non-JVM app. Dashboard and MCP can expose the resulting evidence when enabled.

!!! info "Kafka assertions for non-JVM apps"
    Stove ships bridge libraries to expose `shouldBeConsumed` / `shouldBePublished` for non-JVM apps. The [`stove-kafka`](https://github.com/trendyol/stove/tree/main/go/stove-kafka) Go library supports IBM/sarama (interceptors), twmb/franz-go (hooks), and segmentio/kafka-go (helpers). The library-agnostic core lets you wire any other client (e.g. confluent-kafka-go).

## Next

- [Go overview](go.md). Pick the right mode for your project
- [Go Process Mode](go-process.md). Full walkthrough (HTTP + PG + Kafka + OTel + coverage)
- [Go Container Mode](go-container.md). Production-image parity
- [Provided Application](../Components/19-provided-application.md). Already-running apps (black-box)
- [Dashboard](../Components/18-dashboard.md) · [MCP](../Components/21-mcp.md). Observability
- [Custom systems](../writing-custom-systems.md). Extend Stove
