# Testing Non-JVM Applications with Stove

Stove can test any application that speaks HTTP, databases, and messaging --- regardless of the language. Two starters:

- **`stove-process`** — host binary, fastest iteration loop (`processApp` / `goApp`)
- **`stove-container`** — Docker image, CI parity with the production artifact (`containerApp`). See [container.md](container.md) for the full container guide.

Same Stove DSL, same systems, same env/args mapping. The only difference is *how* the AUT starts.

For Stove + AI agent triage on failed runs, see [mcp.md](mcp.md).

## Requirements

Your application must:

1. **Accept configuration** --- via environment variables, CLI arguments, or both
2. **Handle SIGTERM** --- for clean test teardown
3. **Optional: expose a readiness endpoint** --- HTTP health check, TCP port, or custom probe

## Setup Checklist

```
- [ ] Step 1: Add `stove-process` or `stove-container` dependency
- [ ] Step 2: Create test-e2e source set layout
- [ ] Step 3: Configure Gradle (build app + e2eTest task)
- [ ] Step 4: Create StoveConfig with systems + processApp/goApp
- [ ] Step 5: Instrument app with OpenTelemetry (optional)
- [ ] Step 6: Add Kafka bridge (optional, Go only for now)
- [ ] Step 7: Write tests using stove {} DSL
```

## Step 1: Add dependency

```kotlin
dependencies {
    testImplementation(platform("com.trendyol:stove-bom:$stoveVersion"))
    testImplementation("com.trendyol:stove-process")
    testImplementation("com.trendyol:stove-container") // if AUT runs as Docker image
    // ... other stove dependencies as needed
}
```

## Step 2-3: Project structure, Gradle

Use the source-set and task setup in [gradle-config.md](gradle-config.md). Build your app binary before tests:

```kotlin
val appSourceDir = project.file("my-app")
val appBinary = project.layout.buildDirectory.file("my-app").get().asFile

tasks.register<Exec>("buildApp") {
    workingDir = appSourceDir
    commandLine("go", "build", "-o", appBinary.absolutePath, ".")  // or npm, cargo, etc.
    inputs.files(fileTree(appSourceDir) { include("**/*.go", "go.mod", "go.sum") })
    outputs.file(appBinary)
    doFirst { appBinary.parentFile.mkdirs() }
}

tasks.named<Test>("e2eTest") {
    dependsOn("buildApp")
    systemProperty("go.app.binary", appBinary.absolutePath)
}
```

## Step 4: StoveConfig with processApp / goApp / containerApp

Use `processApp()` for any language binary, `goApp()` as a Go convenience, or `containerApp()` when tests should launch an image directly. `goApp` only accepts `binaryPath`, `target`, and `envProvider`; use `processApp` for arguments, working directory, hooks, or shutdown timeout.

Put setup inside the project's framework lifecycle and pair it with `Stove.stop()`; see [system-setup.md](system-setup.md#reporting). Define `APP_PORT` and `OTLP_PORT` once for the suite. The example includes optional database, Kafka, tracing, and dashboard systems; retain only the ones needed and add their dependencies. Supply the app's `SchemaMigration` implementation.

```kotlin
Stove().with {
    httpClient { HttpClientSystemOptions(baseUrl = "http://localhost:$APP_PORT") }
    tracing { enableSpanReceiver(port = OTLP_PORT) }
    dashboard { DashboardSystemOptions(appName = "my-app") }

    postgresql {
        PostgresqlOptions(
            databaseName = "mydb",
            configureExposedConfiguration = { cfg ->
                listOf(
                    "database.host=${cfg.host}",
                    "database.port=${cfg.port}",
                    "database.name=mydb",
                    "database.username=${cfg.username}",
                    "database.password=${cfg.password}"
                )
            }
        ).migrations { register<SchemaMigration>() }
    }

    kafka {
        KafkaSystemOptions(
            configureExposedConfiguration = { cfg ->
                listOf("kafka.bootstrapServers=${cfg.bootstrapServers}")
            }
        )
    }

    // For Go apps — uses go.app.binary system property by default
    goApp(
        target = ProcessTarget.Server(port = APP_PORT, portEnvVar = "APP_PORT"),
        envProvider = envMapper {
            "database.host" to "DB_HOST"
            "database.port" to "DB_PORT"
            "database.name" to "DB_NAME"
            "database.username" to "DB_USER"
            "database.password" to "DB_PASS"
            "kafka.bootstrapServers" to "KAFKA_BROKERS"
            env("OTEL_EXPORTER_OTLP_ENDPOINT", "http://localhost:$OTLP_PORT")
            "stove.kafka.bridge.port" to "STOVE_KAFKA_BRIDGE_PORT"
        }
    )

    // For any other language — specify the full command
    // processApp {
    //     ProcessApplicationOptions(
    //         command = listOf("python3", "server.py"),
    //         target = ProcessTarget.Server(port = APP_PORT, portEnvVar = "PORT"),
    //         envProvider = envMapper { "database.host" to "DB_HOST" }
    //     )
    // }

    // For apps that prefer CLI arguments instead of env vars
    // processApp {
    //     ProcessApplicationOptions(
    //         command = listOf("/path/to/rust-server"),
    //         target = ProcessTarget.Server(port = APP_PORT),
    //         argsProvider = argsMapper(prefix = "--", separator = "=") {
    //             "database.host" to "db-host"   // --db-host=localhost
    //             "database.port" to "db-port"   // --db-port=5432
    //         }
    //     )
    // }
}.run()
```

### ProcessTarget variants

| Variant | Use case | Default readiness |
|---------|----------|-------------------|
| `ProcessTarget.Server(port, portEnvVar)` | HTTP APIs, gRPC servers, TCP servers | HTTP GET `/health` |
| `ProcessTarget.Worker()` | Kafka consumers, batch jobs, CLI tools | 2-second fixed delay |

### ReadinessStrategy variants

| Strategy | Use case |
|----------|----------|
| `ReadinessStrategy.HttpGet(url, timeout, retries, retryDelay, expectedStatusCodes)` | REST APIs with health endpoint |
| `ReadinessStrategy.TcpPort(port)` | gRPC servers, raw TCP (no HTTP) |
| `ReadinessStrategy.Probe { ... }` | Custom readiness (file, DB query, etc.) |
| `ReadinessStrategy.FixedDelay(duration)` | Simple workers with no readiness signal |

### Configuration passing: envMapper and argsMapper

Two mechanisms to pass Stove configs to the process — use one or both:

**envMapper** — environment variables:

```kotlin
envMapper {
    "stove.config.key" to "ENV_VAR_NAME"    // map Stove config → env var
    env("STATIC_VAR", "value")              // static env var
    env("COMPUTED_VAR") { computeValue() }  // computed env var
}
```

**argsMapper** — CLI arguments (appended to the command):

```kotlin
// --db-host=localhost --db-port=5432
argsMapper(prefix = "--", separator = "=") {
    "database.host" to "db-host"            // map Stove config → CLI flag
    arg("verbose")                          // boolean flag
    arg("log-level", "debug")               // static flag
}

// -h localhost -p 5432 (space separator → two args per flag)
argsMapper(prefix = "-", separator = " ") {
    "database.host" to "h"
    "database.port" to "p"
}
```

## Step 5: OpenTelemetry (optional)

Use your language's OTel SDK. Key points:

- Export completed spans within the assertion window; use a short batch delay or explicit flush. Go's `sdktrace.WithSyncer` is one test-specific option.
- Point the app's OTLP gRPC exporter at the test JVM's receiver; configuring the Gradle Java agent does not instrument a separate process.
- Set **W3C Trace Context propagation** so spans share the test's trace ID; propagate context through asynchronous work as well.
- Stove's HTTP client sends `traceparent` headers automatically

See [tracing.md](tracing.md) for endpoint, port, and instrumentation details.

## Step 6: Kafka bridge (Go only)

For Go apps using IBM/sarama, twmb/franz-go, or segmentio/kafka-go, add the `stove-kafka` bridge library. See [go-setup.md](go-setup.md) for details.

The bridge forwards observed produced/consumed messages via gRPC to Stove. Observation can occur before broker acknowledgment or business processing completes; assert the final outcome as well. See [go-setup.md](go-setup.md#what-the-bridge-proves).

## Code Coverage (Go)

Go 1.20+ supports coverage for built binaries: compile with `go build -cover`, set `GOCOVERDIR` to an existing writable directory, and let the app exit cleanly after SIGTERM. Register the build/report tasks and force test execution on coverage runs; those task names are not built into Stove. See [go-setup.md](go-setup.md#code-coverage) for a composable Gradle example and container mount requirements.

## What you can't do

- **No `bridge()` / `using<T> {}`** --- no access to app's DI container
- Everything else works: HTTP, databases, Kafka, tracing, WireMock, gRPC, dashboard

## Container mode (`containerApp`)

Use `containerApp(...)` from `stove-container` when the AUT should run as a Docker image. Same envMapper/argsMapper model as processApp, plus a `configureContainer { ... }` block for Testcontainers-level customization (network mode, bind mounts, log consumers).

```kotlin
import com.trendyol.stove.container.ContainerTarget
import com.trendyol.stove.container.containerApp
import com.trendyol.stove.system.application.envMapper

containerApp(
    image = "my-app:local",
    target = ContainerTarget.Server(
        hostPort = 8090, internalPort = 8090,
        portEnvVar = "APP_PORT"
    )
)
```

Add dependency configuration only after choosing the network model: aliases/internal ports on a shared network, or a supported host-access setup. The app must listen on a container-accessible interface such as `0.0.0.0`; the JVM's mapped endpoints are not automatically reachable from the container.

`ContainerTarget.Server(hostPort, internalPort, portEnvVar, bindHostPort)` for HTTP/gRPC servers, `ContainerTarget.Worker()` for jobs. See [container.md](container.md) for the full guide (Dockerfile, Gradle wiring, networking strategies, coverage volume mounts, common pitfalls).

A common pattern: one `StoveConfig.kt` branches on `aut.mode=process|container` to switch between starters, with Gradle passing that property into each test JVM as shown in [container.md](container.md#single-stoveconfig-both-modes). Infrastructure and test code can be shared; addresses must fit the selected runner's network.

## MCP triage on failures

When `stove` (the server) is running, agents can triage failed runs through its MCP endpoint instead of scraping logs. The local default is `http://localhost:4040/mcp`; shared internal servers are also supported. See [mcp.md](mcp.md) for the workflow.

## Reference

- Process module source: `starters/process/stove-process/`
- Container module source: `starters/container/stove-container/`
- Container DSL: `starters/container/stove-container/src/main/kotlin/com/trendyol/stove/container/ContainerDsl.kt`
- Full Go example (process + container in one repo): `recipes/process/golang/go-showcase/`
- Docs:
  - `docs/other-languages/go.md` — overview / mode picker
  - `docs/other-languages/go-process.md` — process mode walkthrough
  - `docs/other-languages/go-container.md` — container mode walkthrough
  - `docs/other-languages/index.md`
  - `docs/Components/21-mcp.md` — MCP triage
