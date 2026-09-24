# Go Application Setup with Stove

Use this guide for Go process tests. Add PostgreSQL, Kafka, tracing, dashboard, and coverage only when the application or task needs them.

This skill focuses on **process mode** (`stove-process` / `goApp`) — fastest local iteration. For container-image AUT (`stove-container` / `containerApp`) — language-agnostic, image source is your responsibility — see [container.md](container.md). For agent-driven failure triage via the `stove` CLI MCP endpoint, see [mcp.md](mcp.md).

The same `StoveConfig.kt` can serve both modes by branching on a system property like `-Daut.mode=process|container` (see the showcase recipe).

## Setup Checklist

```
- [ ] Step 1: Identify configuration inputs, readiness, and graceful shutdown
- [ ] Step 2: Add OpenTelemetry instrumentation if traces are needed
- [ ] Step 3: Add Kafka with Stove bridge interceptors (optional)
- [ ] Step 4: Add stove-process dependency (provides goApp() DSL)
- [ ] Step 5: Create test-e2e source set + StoveConfig
- [ ] Step 6: Configure Gradle build (go build + e2eTest)
- [ ] Step 7: Write tests
```

## Step 1: Go app requirements

Make dependency addresses configurable and handle SIGTERM for graceful shutdown. `goApp` accepts a binary path, target, and environment provider. For CLI arguments, working directory, startup hooks, or a longer shutdown timeout, use `processApp` with `ProcessApplicationOptions`.

`ProcessTarget.Server` defaults to GET `/health`; select another URL, a TCP probe, or a custom probe when appropriate. `ProcessTarget.Worker` does not require an HTTP port.

Example environment contract (adapt app-specific names):

| Variable | Purpose |
|----------|---------|
| `APP_PORT` | HTTP listen port |
| `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USER`, `DB_PASS` | PostgreSQL |
| `OTEL_EXPORTER_OTLP_ENDPOINT` | OTLP gRPC endpoint |
| `KAFKA_BROKERS` | Kafka broker addresses |
| `STOVE_KAFKA_BRIDGE_PORT` | Actual Stove Kafka observer gRPC port (test-only) |
| `STOVE_KAFKA_BRIDGE_HOST` | Observer host; defaults to `localhost`, change for container AUTs |
| `GOCOVERDIR` | Directory for Go integration test coverage data (test-only) |

## Step 2: OpenTelemetry

Use an OTLP gRPC exporter pointed at the receiver in the test JVM. Endpoint syntax depends on the SDK API: `otlptracegrpc.WithEndpoint` takes `host:port`, while the standard `OTEL_EXPORTER_OTLP_ENDPOINT` variable takes a URL such as `http://localhost:4317`. Configure plaintext for Stove's local receiver. See [tracing.md](tracing.md) for port selection and propagation.

Illustrative instrumentation hooks (handle setup errors in the application):

```go
// HTTP: wrap mux with otelhttp
handler := otelhttp.NewHandler(mux, "http.request")

// DB: use otelsql instead of database/sql
db, _ := otelsql.Open("postgres", connStr, otelsql.WithAttributes(semconv.DBSystemPostgreSQL))

// Test option: export completed spans synchronously to reduce assertion races.
tp := sdktrace.NewTracerProvider(sdktrace.WithSyncer(exporter))
otel.SetTracerProvider(tp)

// Propagation: must set W3C TraceContext for Stove trace correlation
otel.SetTextMapPropagator(propagation.NewCompositeTextMapPropagator(
    propagation.TraceContext{}, propagation.Baggage{},
))
```

## Step 3: Kafka bridge

The Stove Kafka bridge library lives at `go/stove-kafka/`. It has a library-agnostic core and client-specific subpackages.

### Architecture

```
go/stove-kafka/
  bridge.go           # Core: Bridge, PublishedMessage, ConsumedMessage (library-agnostic)
  sarama/             # IBM/sarama interceptors
    interceptors.go
  franz/              # twmb/franz-go hooks
    hooks.go
  segmentio/            # segmentio/kafka-go helpers
    bridge.go
  stoveobserver/      # Generated gRPC code
```

### Add the dependency

```bash
go get github.com/trendyol/stove/go/stove-kafka
```

### Initialize bridge + wire into your Kafka client

These setup fragments belong inside a function returning an error. Preserve the app's existing Kafka configuration and close clients on shutdown.

**IBM/sarama:**

```go
import (
    "github.com/IBM/sarama"
    stovekafka "github.com/trendyol/stove/go/stove-kafka"
    stovesarama "github.com/trendyol/stove/go/stove-kafka/sarama"
)

bridge, err := stovekafka.NewBridgeFromEnv()
if err != nil {
    return err
}
defer bridge.Close()

config := sarama.NewConfig()
config.Producer.Interceptors = []sarama.ProducerInterceptor{
    &stovesarama.ProducerInterceptor{Bridge: bridge},
}
config.Consumer.Interceptors = []sarama.ConsumerInterceptor{
    &stovesarama.ConsumerInterceptor{Bridge: bridge},
}
```

**twmb/franz-go:**

```go
import (
    stovekafka "github.com/trendyol/stove/go/stove-kafka"
    "github.com/trendyol/stove/go/stove-kafka/franz"
    "github.com/twmb/franz-go/pkg/kgo"
)

bridge, err := stovekafka.NewBridgeFromEnv()
if err != nil {
    return err
}
defer bridge.Close()

client, err := kgo.NewClient(
    kgo.SeedBrokers(brokerList...),
    kgo.WithHooks(&franz.Hook{Bridge: bridge}),
)
if err != nil {
    return err
}
defer client.Close()
```

**segmentio/kafka-go:**

```go
import (
    stovekafka "github.com/trendyol/stove/go/stove-kafka"
    "github.com/trendyol/stove/go/stove-kafka/segmentio"
)

bridge, err := stovekafka.NewBridgeFromEnv()
if err != nil {
    return err
}
defer bridge.Close()

// With a synchronous writer (Async: false), report only successful writes.
if err := writer.WriteMessages(ctx, msgs...); err != nil {
    return err
}
for _, msg := range msgs {
    // Writer.Topic can supply the topic while Message.Topic is empty.
    if msg.Topic == "" {
        msg.Topic = writer.Topic
    }
    segmentio.ReportWritten(ctx, bridge, msg)
}

msg, err := reader.ReadMessage(ctx)
if err != nil {
    return err
}
segmentio.ReportRead(ctx, bridge, msg)
```

### Other libraries (e.g. confluent-kafka-go)

The core bridge has no Kafka client dependency. For any unsupported library, use the core types directly:

```go
import stovekafka "github.com/trendyol/stove/go/stove-kafka"

_ = bridge.ReportPublished(ctx, &stovekafka.PublishedMessage{
    Topic: msg.Topic, Key: string(msg.Key), Value: msg.Value, Headers: myHeaders(msg),
})
_ = bridge.ReportConsumed(ctx, &stovekafka.ConsumedMessage{
    Topic: msg.Topic, Key: string(msg.Key), Value: msg.Value,
    Partition: msg.Partition, Offset: msg.Offset, Headers: myHeaders(msg),
})
_ = bridge.ReportCommitted(ctx, msg.Topic, msg.Partition, msg.Offset+1)
```

### What the bridge proves

- Sarama reports before send and on consumption; franz-go reports records entering its produce/fetch buffers. These hooks do not prove broker acknowledgment or handler completion.
- Consumer helpers pre-report `offset+1` to satisfy Stove's observer commit check. That report is bookkeeping, not confirmation of a broker commit.
- For processing tests, follow observation with a bounded assertion on the business outcome (database state, response, or output event). Match a unique message ID and the expected topic.
- With no bridge port set, `NewBridgeFromEnv` returns a nil bridge; bridge methods and helpers return without reporting. Do not enable the bridge in ordinary production configuration.
- Async producers need delivery-completion/error handling. A successful enqueue is not a successful publish.

### Kafka timing and isolation

Create topics before sending, or enable auto-topic creation where supported. Choose batch/flush settings that fit the test timeout and check send errors. Preserve the app's acknowledgment behavior; short auto-commit intervals are not needed for the Go helper's pre-reported commit.

Use unique message IDs and a distinct consumer group per test run when offsets must be isolated. A name such as `"myapp-" + library` only separates libraries; include a run identifier to separate runs. `earliest` only applies when no valid committed offset exists.

Use the Kafka library already used by the application. Running Sarama, franz-go, and segmentio as a matrix is a showcase feature, not a Stove requirement. A single franz-go client supports producing and consuming; diagnose delivery/rebalance delays before introducing separate clients.

## Step 4: Add stove-process dependency

The `stove-process` module provides `goApp()` out of the box — no custom `ApplicationUnderTest` needed. `goApp` supports `envMapper`; use the underlying `processApp` for `argsMapper` and other process options.

```kotlin
dependencies {
    testImplementation(platform("com.trendyol:stove-bom:$stoveVersion"))
    testImplementation("com.trendyol:stove-process")
    testImplementation("com.trendyol:stove-http")
    testImplementation("com.trendyol:stove-extensions-kotest") // or stove-extensions-junit
}
```

Source: `starters/process/stove-process/`

## Step 5: StoveConfig

Create/reuse the source set, test engine, and lifecycle in [gradle-config.md](gradle-config.md) and [system-setup.md](system-setup.md#reporting). Put this setup in Kotest `beforeProject()` or the existing JUnit lifecycle and call `Stove.stop()` in teardown.

The example below includes PostgreSQL, Kafka, tracing, and dashboard; add their matching Stove modules only if keeping those blocks. Define `APP_PORT` and `OTLP_PORT` once for the suite and use distinct ports for concurrent suites. Supply the app-specific `ProductMigration` or use the app's schema initialization.

```kotlin
Stove().with {
    httpClient { HttpClientSystemOptions(baseUrl = "http://localhost:$APP_PORT") }
    dashboard { DashboardSystemOptions(appName = "go-showcase") }
    tracing { enableSpanReceiver(port = OTLP_PORT) }

    kafka {
        KafkaSystemOptions(
            configureExposedConfiguration = { cfg ->
                listOf("kafka.bootstrapServers=${cfg.bootstrapServers}")
            }
        )
    }

    postgresql {
        PostgresqlOptions(
            databaseName = "stove",
            configureExposedConfiguration = { cfg ->
                listOf(
                    "database.host=${cfg.host}", "database.port=${cfg.port}",
                    "database.name=stove",
                    "database.username=${cfg.username}", "database.password=${cfg.password}"
                )
            }
        ).migrations { register<ProductMigration>() }
    }

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
}.run()
```

Current standalone Stove exposes the bound observer port as `stove.kafka.bridge.port`; map that value rather than a global default, especially for keyed systems. For older releases without this entry, explicitly configure `bridgeGrpcServerPort` and pass the same port to the app. Go's host defaults to `localhost`; container mode also needs a reachable `STOVE_KAFKA_BRIDGE_HOST`.

## Step 6: Gradle

Configure the existing `e2eTest` task from [gradle-config.md](gradle-config.md); do not disable it or register the same name twice:

```kotlin
val goSourceDir = projectDir // Directory containing go.mod; adapt to the project.
val goBinary = layout.buildDirectory.file("go-app").get().asFile

tasks.register<Exec>("buildGoApp") {
    workingDir = goSourceDir
    commandLine("go", "build", "-o", goBinary.absolutePath, ".")
    inputs.files(fileTree(goSourceDir) {
        include("**/*.go", "go.mod", "go.sum")
        exclude("build/**", ".gradle/**") // Keep Gradle outputs out of the Go inputs.
    })
    outputs.file(goBinary)
    doFirst { goBinary.parentFile.mkdirs() }
}

tasks.named<Test>("e2eTest") {
    dependsOn("buildGoApp")
    systemProperty("go.app.binary", goBinary.absolutePath)
}
```

Declare embedded assets (`//go:embed`), local replacement modules, workspace files, and build flags as inputs when used. Adapt `.` to the actual main package (for example, `./cmd/server`) and the exclusions if Gradle's build directory is customized. New matrix `Test` tasks need the same `testClassesDirs`, `classpath`, and `useJUnitPlatform()` wiring as the base task; serialize runs that share ports.

## Step 7: Write tests

```kotlin
class GoShowcaseTest : FunSpec({
    test("create product, verify DB + Kafka + traces") {
        stove {
            var productId: String? = null

            http {
                postAndExpectBody<ProductResponse>(
                    uri = "/api/products",
                    body = CreateProductRequest(name = "Test", price = 42.99).some()
                ) { actual ->
                    actual.status shouldBe 201
                    productId = actual.body().id
                }
            }

            postgresql {
                shouldQuery<ProductRow>(
                    query = "SELECT id, name, price FROM products WHERE id = '$productId'",
                    mapper = { row -> ProductRow(row.string("id"), row.string("name"), row.double("price")) }
                ) { rows -> rows.size shouldBe 1 }
            }

            kafka {
                shouldBePublished<ProductCreatedEvent>(10.seconds) {
                    actual.name == "Test"
                }
            }

            tracing {
                waitForSpans(4, 5000)
                shouldContainSpan("http.request")
                shouldNotHaveFailedSpans()
            }
        }
    }

    test("consume Kafka events") {
        stove {
            var productId: String? = null

            http {
                postAndExpectBody<ProductResponse>(
                    uri = "/api/products",
                    body = CreateProductRequest(name = "Original", price = 10.0).some()
                ) { actual -> productId = actual.body().id }
            }

            kafka {
                publish("product.update", ProductUpdateEvent(id = productId!!, name = "Updated", price = 99.99))
                shouldBeConsumed<ProductUpdateEvent>(10.seconds) {
                    actual.id == productId && actual.name == "Updated"
                }
            }

            // import io.kotest.assertions.nondeterministic.eventually
            // The Go hook can report consumption before the database update.
            eventually(10.seconds) {
                postgresql {
                    shouldQuery<ProductRow>(
                        query = "SELECT id, name, price FROM products WHERE id = '$productId'",
                        mapper = { row -> ProductRow(row.string("id"), row.string("name"), row.double("price")) }
                    ) { rows -> rows.first().name shouldBe "Updated" }
                }
            }
        }
    }
})
```

## Code Coverage

Go 1.20+ supports coverage of built binaries. Compile with `go build -cover`, provide an existing `GOCOVERDIR`, and make the application's SIGTERM handler return cleanly from `main` after flushing/shutting down. Forced termination or an unhandled signal can lose coverage; increase the `processApp` shutdown timeout if necessary.

### Gradle setup

Add this to the build from Step 6. It configures existing tasks and creates coverage tasks only with `-Pgo.coverage=true`:

```kotlin
val coverageEnabled = providers.gradleProperty("go.coverage")
    .map { it.toBoolean() }.getOrElse(false)
val goCoverDir = layout.buildDirectory.dir("go-coverage/raw").get().asFile
val goCoverProfile = layout.buildDirectory.file("go-coverage/coverage.out").get().asFile
val goCoverHtml = layout.buildDirectory.file("go-coverage/coverage.html").get().asFile

tasks.named<Exec>("buildGoApp") {
    inputs.property("coverageEnabled", coverageEnabled)
    if (coverageEnabled) {
        commandLine("go", "build", "-cover", "-o", goBinary.absolutePath, ".")
    }
}

if (coverageEnabled) {
    val prepareGoCoverage = tasks.register<Delete>("prepareGoCoverage") {
        delete(goCoverDir)
    }
    tasks.named<Test>("e2eTest") {
        dependsOn(prepareGoCoverage)
        systemProperty("go.cover.dir", goCoverDir.absolutePath)
        outputs.upToDateWhen { false }
        outputs.cacheIf { false }
        doFirst { goCoverDir.mkdirs() }
    }
    val goCoverageReport = tasks.register<Exec>("goCoverageReport") {
        dependsOn("e2eTest")
        workingDir = goSourceDir
        commandLine("go", "tool", "covdata", "textfmt",
            "-i=${goCoverDir.absolutePath}", "-o=${goCoverProfile.absolutePath}")
    }
    val goCoverageSummary = tasks.register<Exec>("goCoverageSummary") {
        dependsOn(goCoverageReport)
        workingDir = goSourceDir
        commandLine("go", "tool", "cover", "-func=${goCoverProfile.absolutePath}")
    }
    val goCoverageHtmlTask = tasks.register<Exec>("goCoverageHtml") {
        dependsOn(goCoverageReport)
        workingDir = goSourceDir
        commandLine("go", "tool", "cover",
            "-html=${goCoverProfile.absolutePath}", "-o=${goCoverHtml.absolutePath}")
    }
    tasks.register("e2eTestWithCoverage") {
        dependsOn(goCoverageSummary, goCoverageHtmlTask)
    }
}
```

The report tasks run after successful test completion and process shutdown. They require actual raw coverage data. Disabling the build cache alone does not disable Gradle's up-to-date skipping, so coverage runs disable both for the test task. Use a separate raw directory per task/shard if adapting this to a matrix.

### StoveConfig

Inside the runner's `envMapper`, pass the directory when coverage is enabled:

```kotlin
System.getProperty("go.cover.dir")?.let { env("GOCOVERDIR", it) }
```

For container coverage, compile the image with coverage enabled, bind-mount the raw directory, and set `GOCOVERDIR` to its container path. The mount must be writable by the image's user; setting a host path in the environment alone does not mount it. See [container.md](container.md#step-6-bind-mounts-optional).

If logs show SIGPIPE/exit 141 when a JVM-owned stdout pipe closes, diagnose that shutdown path; Go's `signal.Ignore(syscall.SIGPIPE)` can be appropriate there. It is not a universal prerequisite for coverage.

## Running

From the downstream project, using its actual module/task path:

```bash
./gradlew e2eTest
./gradlew e2eTestWithCoverage -Pgo.coverage=true
# Coverage HTML: build/go-coverage/coverage.html
```

The upstream `recipes/process/golang/go-showcase/` additionally has per-library and container tasks. Those names are recipe-specific; inspect its build before using them.

## Go dependencies

```
github.com/trendyol/stove/go/stove-kafka                        # Stove Kafka bridge (core)
github.com/trendyol/stove/go/stove-kafka/sarama                 # IBM/sarama interceptors
github.com/trendyol/stove/go/stove-kafka/franz                  # twmb/franz-go hooks
github.com/trendyol/stove/go/stove-kafka/segmentio                # segmentio/kafka-go helpers
github.com/XSAM/otelsql                                         # database/sql instrumentation
go.opentelemetry.io/contrib/instrumentation/net/http/otelhttp    # HTTP instrumentation
go.opentelemetry.io/otel/exporters/otlp/otlptrace/otlptracegrpc # OTLP exporter
google.golang.org/grpc                                           # gRPC
```

## Reference

- Process module (goApp DSL): `starters/process/stove-process/`
- Container module (containerApp DSL): `starters/container/stove-container/`
- Full working example (process + container in one repo): `recipes/process/golang/go-showcase/`
- Bridge library source: `go/stove-kafka/`
- Docs:
  - `docs/other-languages/go.md` — overview / mode picker
  - `docs/other-languages/go-process.md` — process mode walkthrough
  - `docs/other-languages/go-container.md` — container mode walkthrough
- Sibling skills:
  - [container.md](container.md) — language-agnostic container AUT
  - [mcp.md](mcp.md) — MCP triage on failed runs
  - [other-languages.md](other-languages.md) — non-JVM overview
