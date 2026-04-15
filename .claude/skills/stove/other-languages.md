# Testing Non-JVM Applications with Stove

Stove can test any application that speaks HTTP, databases, and messaging --- regardless of the language. The `stove-process` module provides a ready-to-use `ApplicationUnderTest` that manages OS processes. No custom implementation needed.

## Requirements

Your application must:

1. **Accept configuration** --- via environment variables, CLI arguments, or both
2. **Handle SIGTERM** --- for clean test teardown
3. **Optional: expose a readiness endpoint** --- HTTP health check, TCP port, or custom probe

## Setup Checklist

```
- [ ] Step 1: Add stove-process dependency
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
    // ... other stove dependencies as needed
}
```

## Step 2-3: Project structure, Gradle

Same as JVM setup (see SKILL.md). Build your app binary before tests:

```kotlin
val appSourceDir = project.file("my-app")
val appBinary = project.layout.buildDirectory.file("my-app").get().asFile

tasks.register<Exec>("buildApp") {
    workingDir = appSourceDir
    commandLine("go", "build", "-o", appBinary.absolutePath, ".")  // or npm, cargo, etc.
    inputs.files(fileTree(appSourceDir) { include("*.go", "go.mod", "go.sum") })
    outputs.file(appBinary)
}

tasks.named<Test>("e2eTest") {
    dependsOn("buildApp")
    systemProperty("app.binary", appBinary.absolutePath)
}
```

## Step 4: StoveConfig with processApp / goApp

Use `processApp()` for any language, or `goApp()` as a Go convenience:

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
            env("OTEL_EXPORTER_OTLP_ENDPOINT", "localhost:$OTLP_PORT")
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
| `ReadinessStrategy.HttpGet(HealthCheckOptions(...))` | REST APIs with health endpoint |
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

- Use **sync exporter** (`WithSyncer`) for tests, not batched
- Set **W3C Trace Context propagation** so spans share the test's trace ID
- Stove's HTTP client sends `traceparent` headers automatically

## Step 6: Kafka bridge (Go only)

For Go apps using IBM/sarama, twmb/franz-go, or segmentio/kafka-go, add the `stove-kafka` bridge library. See [go-setup.md](go-setup.md) for details.

The bridge intercepts produced/consumed messages and forwards them via gRPC to Stove's observer, enabling `shouldBePublished` and `shouldBeConsumed` assertions.

## What you can't do

- **No `bridge()` / `using<T> {}`** --- no access to app's DI container
- Everything else works: HTTP, databases, Kafka, tracing, WireMock, gRPC, dashboard

## Reference

- Process module source: `starters/process/stove-process/`
- Full Go example: `recipes/process/golang/go-showcase/`
- Docs: `docs/other-languages/go.md` and `docs/other-languages/index.md`
