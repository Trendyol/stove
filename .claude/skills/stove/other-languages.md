# Testing Non-JVM Applications with Stove

Stove can test any application that speaks HTTP, databases, and messaging --- regardless of the language. The application runs as an OS process; Stove manages infrastructure, passes config via env vars, and runs Kotlin e2e tests against it.

## Requirements

Your application must:

1. **Read environment variables** --- database URLs, ports, credentials, broker addresses
2. **Expose an HTTP health endpoint** --- for Stove to detect readiness
3. **Shut down on SIGTERM** --- for clean test teardown

## Setup Checklist

```
- [ ] Step 1: Create ApplicationUnderTest implementation
- [ ] Step 2: Create DSL helper function (e.g., goApp(), nodeApp())
- [ ] Step 3: Create test-e2e source set layout
- [ ] Step 4: Configure Gradle (build app + e2eTest task)
- [ ] Step 5: Create StoveConfig with systems + configMapper
- [ ] Step 6: Instrument app with OpenTelemetry (optional)
- [ ] Step 7: Add Kafka bridge (optional, Go only for now)
- [ ] Step 8: Write tests using stove {} DSL
```

## Step 1: Implement ApplicationUnderTest

Start the app as an OS process, pass configs as env vars, wait for health:

```kotlin
@StoveDsl
class MyAppUnderTest(
    private val binaryPath: String,
    private val port: Int,
    private val configMapper: (List<String>) -> Map<String, String>
) : ApplicationUnderTest<Unit> {

    private var process: Process? = null

    override suspend fun start(configurations: List<String>) {
        val envVars = configMapper(configurations)
        val processBuilder = ProcessBuilder(binaryPath)
            .redirectErrorStream(true)
        processBuilder.environment().putAll(envVars)
        processBuilder.environment()["APP_PORT"] = port.toString()

        process = processBuilder.start()
        // Read stdout in background to prevent buffer blocking
        launchOutputReader(process!!)
        waitForHealth("http://localhost:$port/health")
    }

    override suspend fun stop() {
        process?.let { p ->
            p.destroy()  // SIGTERM
            if (!p.waitFor(5, TimeUnit.SECONDS)) {
                p.destroyForcibly()
            }
        }
    }
}
```

## Step 2: DSL helper

```kotlin
fun WithDsl.myApp(
    binaryPath: String = System.getProperty("app.binary")
        ?: error("app.binary system property not set"),
    port: Int,
    configMapper: (List<String>) -> Map<String, String>
): Stove {
    this.stove.applicationUnderTest(MyAppUnderTest(binaryPath, port, configMapper))
    return this.stove
}
```

## Step 3-5: Project structure, Gradle, StoveConfig

Same as JVM setup (see SKILL.md), except:

- **No `springBoot()` / `ktor()` runner** --- use your custom DSL helper instead
- **No `bridge()`** --- the app runs as a separate process, no DI access
- **configMapper** translates Stove's `key=value` configs to your app's env vars

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

    myApp(
        port = APP_PORT,
        configMapper = { configs ->
            val map = configs.associate { line ->
                val (key, value) = line.split("=", limit = 2)
                key to value
            }
            buildMap {
                map["database.host"]?.let { put("DB_HOST", it) }
                map["database.port"]?.let { put("DB_PORT", it) }
                // ... map all configs to your app's env vars
                put("OTEL_EXPORTER_OTLP_ENDPOINT", "localhost:$OTLP_PORT")
            }
        }
    )
}.run()
```

## Step 6: OpenTelemetry (optional)

Use your language's OTel SDK. Key points:

- Use **sync exporter** (`WithSyncer`) for tests, not batched
- Set **W3C Trace Context propagation** so spans share the test's trace ID
- Stove's HTTP client sends `traceparent` headers automatically

## Step 7: Kafka bridge (Go only)

For Go apps using IBM/sarama, add the `stove-kafka` bridge library. See [go-setup.md](go-setup.md) for details.

The bridge intercepts produced/consumed messages and forwards them via gRPC to Stove's observer, enabling `shouldBePublished` and `shouldBeConsumed` assertions.

## What you can't do

- **No `bridge()` / `using<T> {}`** --- no access to app's DI container
- Everything else works: HTTP, databases, Kafka, tracing, WireMock, gRPC, dashboard

## Gradle build task

Build the app binary before tests:

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

## Reference

- Full Go example: `recipes/go-recipes/go-showcase/`
- Docs: `docs/other-languages/go.md` and `docs/other-languages/index.md`
