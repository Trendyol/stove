# Go. Process Mode

Run the Go binary directly as the application under test using `stove-process` and the `goApp()` DSL. This is the fastest iteration loop: no image build, no registry, just `go build` and run.

For container-based AUT (CI parity with the production image), see [Container Mode](go-container.md).

## What this guide covers

End-to-end Go testing with HTTP, PostgreSQL, Kafka (sarama / franz-go / segmentio), distributed tracing, dashboard streaming, MCP triage, and integration coverage.

The full source is at [`recipes/process/golang/go-showcase`](https://github.com/Trendyol/stove/tree/main/recipes/process/golang/go-showcase).

## Project Structure

```
go-showcase/                   # Standalone Gradle project (copy-paste ready)
  main.go                      # Entry point, env var config, graceful shutdown
  db.go                        # PostgreSQL queries (auto-traced via otelsql)
  handlers.go                  # HTTP handlers + Kafka publish (auto-traced via otelhttp)
  kafka.go                     # KafkaProducer interface, factory, shared consumer handler
  kafka_sarama.go              # IBM/sarama implementation
  kafka_franz.go               # twmb/franz-go implementation
  kafka_segmentio.go           # segmentio/kafka-go implementation
  tracing.go                   # OpenTelemetry SDK initialization
  go.mod
  stovetests/                  # Kotlin Stove tests
    kotlin/com/.../e2e/
      setup/
        StoveConfig.kt              # Single setup file (switches process/container via go.aut.mode)
        ProductMigration.kt         # Creates products table
      tests/
        GoShowcaseTest.kt           # E2E tests
    resources/
      kotest.properties
  build.gradle.kts             # Builds Go + runs Kotlin tests
  settings.gradle.kts

# Published Go library used by the showcase:
go/stove-kafka/                # Stove Kafka bridge for Go applications
  bridge.go                    # Core bridge (library-agnostic gRPC client)
  sarama/                      # IBM/sarama interceptors
  franz/                       # twmb/franz-go hooks
  segmentio/                   # segmentio/kafka-go helpers
  stoveobserver/               # Generated gRPC code from messages.proto
  go.mod
```

## The Go Application

A minimal HTTP + PostgreSQL service. The key design choice: <span data-rn="underline" data-rn-color="#009688">all tracing is in the infrastructure layer</span>, not in business logic.

### Entry Point

```go title="main.go"
func main() {
    // Ignore SIGPIPE so log writes to a closed stdout pipe don't kill the process
    // when running under ProcessBuilder. Critical for graceful shutdown + coverage flush.
    signal.Ignore(syscall.SIGPIPE)

    ctx := context.Background()
    port := getEnv("APP_PORT", "8080")

    shutdownTracing, _ := initTracing(ctx, "go-showcase")
    defer shutdownTracing(ctx)

    db, _ := initDB(connStr)  // otelsql wraps database/sql automatically
    defer db.Close()

    bridge, _ := stovekafka.NewBridgeFromEnv()  // nil in production. zero overhead
    defer bridge.Close()

    kafkaLibrary := getEnv("KAFKA_LIBRARY", "sarama")
    producer, stopKafka, _ := initKafka(kafkaLibrary, brokers, db, bridge)
    defer stopKafka()

    mux := http.NewServeMux()
    registerRoutes(mux, db, producer)

    handler := otelhttp.NewHandler(mux, "http.request")
    server := &http.Server{Addr: ":" + port, Handler: handler}
    // ... graceful shutdown on SIGTERM
}
```

Configuration comes entirely from environment variables:

| Variable | Purpose | Default |
|----------|---------|---------|
| `APP_PORT` | HTTP listen port | `8080` |
| `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USER`, `DB_PASS` | PostgreSQL connection | `localhost`, `5432`, `stove`, `sa`, `sa` |
| `OTEL_EXPORTER_OTLP_ENDPOINT` | OTLP gRPC endpoint for traces | *(disabled if empty)* |
| `KAFKA_BROKERS` | Comma-separated Kafka broker addresses | *(disabled if empty)* |
| `KAFKA_LIBRARY` | Kafka client library: `sarama`, `franz`, or `segmentio` | `sarama` |
| `STOVE_KAFKA_BRIDGE_PORT` | Stove Kafka bridge gRPC port | *(disabled if empty, test-only)* |
| `GOCOVERDIR` | Directory for Go integration test coverage data | *(disabled if empty, test-only)* |

### Handlers, DB, Tracing

Handlers and DB code are pure business logic. No tracing imports. Because `otelhttp` and `otelsql` instrument transparently. See the [container guide](go-container.md) and the showcase repo for the full code; the same files are used in both modes.

!!! tip "Sync vs Batch Exporter"
    Use `sdktrace.WithSyncer(exporter)` for tests so spans are exported immediately when they end. In production, use `WithBatcher(exporter)` for performance. The 5-second default batch interval would cause test assertions to fail because spans wouldn't arrive in time.

!!! info "W3C Trace Context Propagation"
    Setting `propagation.TraceContext{}` is essential. Stove's HTTP client sends a `traceparent` header with each request. The `otelhttp` middleware extracts it, so all spans in the Go app share the same trace ID as the test. And the [Stove Dashboard](../Components/18-dashboard.md) and [MCP](../Components/21-mcp.md) tools can correlate them with the failure.

## Kafka. `stove-kafka` bridge

Stove provides a Go bridge library (`stove-kafka`) that enables `shouldBeConsumed` and `shouldBePublished` assertions for Go applications. The bridge forwards produced/consumed messages over gRPC to Stove's `StoveKafkaObserverGrpcServer`. The core is library-agnostic; client-specific subpackages provide interceptors/hooks for popular Go Kafka libraries:

| Library | Subpackage | Integration |
|---------|-----------|-------------|
| [IBM/sarama](https://github.com/IBM/sarama) | `sarama` | `ProducerInterceptor` / `ConsumerInterceptor` |
| [twmb/franz-go](https://github.com/twmb/franz-go) | `franz` | `kgo.WithHooks(&franz.Hook{...})` |
| [segmentio/kafka-go](https://github.com/segmentio/kafka-go) | `segmentio` | `segmentio.ReportWritten()` / `segmentio.ReportRead()` |

!!! tip "Using other Kafka libraries (e.g. confluent-kafka-go)"
    The subpackages above are conveniences. The core bridge (`PublishedMessage`, `ConsumedMessage`, `Bridge`) has **no Kafka client dependency**. For any library not listed above, import only the core package and call `bridge.ReportPublished()`, `bridge.ReportConsumed()`, and `bridge.ReportCommitted()` directly with your own type conversion.

In production, `STOVE_KAFKA_BRIDGE_PORT` is not set, so `NewBridgeFromEnv()` returns `nil`. All Bridge methods are nil-safe no-ops. Zero overhead.

### Integrating the Bridge

```bash
go get github.com/trendyol/stove/go/stove-kafka
```

```go
import stovekafka "github.com/trendyol/stove/go/stove-kafka"

bridge, _ := stovekafka.NewBridgeFromEnv()
defer bridge.Close()
```

Wire into your client:

=== "IBM/sarama"

    ```go
    import stovesarama "github.com/trendyol/stove/go/stove-kafka/sarama"

    config := sarama.NewConfig()
    config.Producer.Interceptors = []sarama.ProducerInterceptor{
        &stovesarama.ProducerInterceptor{Bridge: bridge},
    }
    config.Consumer.Interceptors = []sarama.ConsumerInterceptor{
        &stovesarama.ConsumerInterceptor{Bridge: bridge},
    }
    ```

=== "twmb/franz-go"

    ```go
    import "github.com/trendyol/stove/go/stove-kafka/franz"

    client, err := kgo.NewClient(
        kgo.SeedBrokers("localhost:9092"),
        kgo.WithHooks(&franz.Hook{Bridge: bridge}),
    )
    ```

=== "segmentio/kafka-go"

    ```go
    import "github.com/trendyol/stove/go/stove-kafka/segmentio"

    err := writer.WriteMessages(ctx, msgs...)
    segmentio.ReportWritten(ctx, bridge, msgs...)

    msg, err := reader.ReadMessage(ctx)
    segmentio.ReportRead(ctx, bridge, msg)
    ```

When `Bridge` is nil (production), all interceptors/helpers return immediately with zero overhead.

### Test-Friendly Kafka Settings

When running against Testcontainers, configure Kafka clients for **fast feedback**:

- **Auto-create topics**. The test container may not have topics pre-created
- **Small batch size / low batch timeout**. Flush produces immediately
- **Short auto-commit interval**. Make consumed offsets visible to Stove quickly

=== "IBM/sarama"

    ```go
    config := sarama.NewConfig()
    config.Producer.Return.Successes = true
    config.Consumer.Offsets.Initial = sarama.OffsetOldest
    config.Consumer.Offsets.AutoCommit.Interval = 100 * time.Millisecond
    ```

=== "twmb/franz-go"

    ```go
    kgo.AllowAutoTopicCreation(),
    kgo.AutoCommitInterval(100 * time.Millisecond),
    kgo.ConsumeResetOffset(kgo.NewOffset().AtStart()),
    ```

=== "segmentio/kafka-go"

    ```go
    writer := &kafka.Writer{
        BatchSize:              1,
        BatchTimeout:           10 * time.Millisecond,
        AllowAutoTopicCreation: true,
    }
    reader := kafka.NewReader(kafka.ReaderConfig{
        CommitInterval: 100 * time.Millisecond,
        MaxWait:        500 * time.Millisecond,
    })
    ```

!!! warning "Production vs Test settings"
    These aggressive settings are optimized for test speed, not throughput. In production, use larger batch sizes, longer commit intervals, and broker-managed topic creation.

### Consumer Groups

Each Kafka library run uses a unique consumer group ID (`"go-showcase-" + library`) to prevent offset carryover between sequential test runs.

## Stove Test Setup

### Gradle Build

```kotlin title="build.gradle.kts"
val goBinary = layout.buildDirectory.file("go-app").get().asFile
val goExecutable = providers.environmentVariable("GO_EXECUTABLE").getOrElse("go")
val coverageEnabled = providers.gradleProperty("go.coverage")
    .map { it.toBoolean() }.getOrElse(false)

tasks.register<Exec>("buildGoApp") {
    description = "Compiles the Go application."
    group = "build"
    val args = mutableListOf(goExecutable, "build")
    if (coverageEnabled) args.add("-cover")
    args.addAll(listOf("-o", goBinary.absolutePath, "."))
    commandLine(args)
    inputs.files(fileTree(".") { include("*.go", "go.mod", "go.sum") })
    outputs.file(goBinary)
}

// Per-library e2e test tasks
val kafkaLibraries = listOf("sarama", "franz", "segmentio")
val kafkaE2eTasks = kafkaLibraries.mapIndexed { index, lib ->
    tasks.register<Test>("e2eTest_$lib") {
        dependsOn("buildGoApp")
        systemProperty("go.aut.mode", "process")
        systemProperty("go.app.binary", goBinary.absolutePath)
        systemProperty("kafka.library", lib)
        if (index > 0) mustRunAfter("e2eTest_${kafkaLibraries[index - 1]}")
    }
}
tasks.named<Test>("e2eTest") { dependsOn(kafkaE2eTasks); enabled = false }

dependencies {
    testImplementation(stoveLibs.stove)
    testImplementation(stoveLibs.stoveProcess)
    testImplementation(stoveLibs.stovePostgres)
    testImplementation(stoveLibs.stoveHttp)
    testImplementation(stoveLibs.stoveTracing)
    testImplementation(stoveLibs.stoveDashboard)
    testImplementation(stoveLibs.stoveKafka)
    testImplementation(stoveLibs.stoveExtensionsKotest)
}
```

### Stove Configuration

```kotlin title="StoveConfig.kt"
Stove()
    .with {
        httpClient {
            HttpClientSystemOptions(baseUrl = "http://localhost:$APP_PORT")
        }

        dashboard {
            DashboardSystemOptions(appName = "go-showcase")
        }

        tracing {
            enableSpanReceiver(port = OTLP_PORT)
        }

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
                        "database.host=${cfg.host}",
                        "database.port=${cfg.port}",
                        "database.name=stove",
                        "database.username=${cfg.username}",
                        "database.password=${cfg.password}"
                    )
                }
            ).migrations {
                register<ProductMigration>()
            }
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
                env("OTEL_EXPORTER_OTLP_ENDPOINT", "localhost:$OTLP_PORT")
                env("KAFKA_LIBRARY") { System.getProperty("kafka.library") ?: "sarama" }
                env("STOVE_KAFKA_BRIDGE_PORT", stoveKafkaBridgePortDefault)
                env("GOCOVERDIR") {
                    System.getProperty("go.cover.dir")
                        ?.also { java.io.File(it).mkdirs() } ?: ""
                }
            }
        )
    }.run()
```

The `envMapper` block declaratively maps Stove's exposed configurations to environment variables the Go app expects. Use `"stoveKey" to "ENV_VAR"` for config-derived values and `env("NAME", "value")` for static ones. For apps that prefer CLI arguments, use `argsMapper` instead (or alongside).

### Database Migration

```kotlin title="ProductMigration.kt"
class ProductMigration : DatabaseMigration<PostgresSqlMigrationContext> {
    override val order: Int = 1

    override suspend fun execute(connection: PostgresSqlMigrationContext) {
        connection.sql.execute(
            queryOf("""
                CREATE TABLE IF NOT EXISTS products (
                    id VARCHAR(255) PRIMARY KEY,
                    name VARCHAR(255) NOT NULL,
                    price DECIMAL(10, 2) NOT NULL
                )
            """).asExecute
        )
    }
}
```

## Writing Tests

```kotlin title="GoShowcaseTest.kt"
class GoShowcaseTest : FunSpec({
    test("create product, verify HTTP, DB, Kafka, traces") {
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
                    mapper = productRowMapper
                ) { rows -> rows.size shouldBe 1 }
            }

            kafka {
                shouldBePublished<ProductCreatedEvent> {
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
})
```

Verify the Go app consumes events and updates state:

```kotlin
test("consume product update events from Kafka") {
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
            shouldBeConsumed<ProductUpdateEvent> {
                actual.id == productId && actual.name == "Updated"
            }
        }

        postgresql {
            shouldQuery<ProductRow>(
                query = "SELECT id, name, price FROM products WHERE id = '$productId'",
                mapper = productRowMapper
            ) { rows -> rows.first().name shouldBe "Updated" }
        }
    }
}
```

## Dashboard & MCP

When the [`stove` CLI](../Components/18-dashboard.md) is running, the Go run streams to `http://localhost:4040` like any JVM run. Timeline, traces, snapshots, Kafka explorer.

For AI-assisted triage, the same CLI exposes a [Model Context Protocol endpoint](../Components/21-mcp.md) at `http://localhost:4040/mcp`. Agents call `stove_failures` to discover failed Go tests, then `stove_failure_detail`, `stove_timeline`, `stove_trace`, and `stove_snapshot` for compact, structured evidence. No log scraping required.

## Code Coverage

For both process-mode and container-mode AUT runs, Stove executes your app outside `go test`, so standard `go test -cover` doesn't apply. Go 1.20+ integration coverage fits this model: build with `go build -cover`, set `GOCOVERDIR`, and flush data on graceful shutdown (SIGTERM).

### How It Works

```
1. go build -cover          → instruments the binary
2. GOCOVERDIR=/path         → tells the binary where to write coverage data
3. SIGTERM (Stove stop)     → graceful shutdown triggers coverage flush
4. go tool covdata textfmt  → converts raw data to standard coverage.out
5. go tool cover -func/-html → human-readable reports
```

### Gradle Setup

The recipe supports coverage via the `-Pgo.coverage=true` Gradle property. When disabled (default), there is zero overhead.

```kotlin title="build.gradle.kts"
val coverageEnabled = providers.gradleProperty("go.coverage")
    .map { it.toBoolean() }.getOrElse(false)
val goCoverDirPath = layout.buildDirectory.dir("go-coverage").get().asFile.absolutePath

tasks.register<Exec>("buildGoApp") {
    val args = mutableListOf(goExecutable, "build")
    if (coverageEnabled) args.add("-cover")
    args.addAll(listOf("-o", goBinary.absolutePath, "."))
    commandLine(args)
}

tasks.register<Test>("e2eTest_sarama") {
    if (coverageEnabled) {
        systemProperty("go.cover.dir", goCoverDirPath)
        outputs.cacheIf { false }  // Coverage data is a side effect
    }
}

if (coverageEnabled) {
    tasks.register<Exec>("goCoverageReport") {
        mustRunAfter(kafkaE2eTasks)
        commandLine(goExecutable, "tool", "covdata", "textfmt",
            "-i=$goCoverDirPath", "-o=$goCoverOutPath")
    }
    tasks.register<Exec>("goCoverageSummary") {
        dependsOn("goCoverageReport")
        commandLine(goExecutable, "tool", "cover", "-func=$goCoverOutPath")
    }
    tasks.register<Exec>("goCoverageHtml") {
        dependsOn("goCoverageReport")
        commandLine(goExecutable, "tool", "cover", "-html=$goCoverOutPath", "-o=coverage.html")
    }
    tasks.register("e2eTestWithCoverage") {
        dependsOn(kafkaE2eTasks)
        finalizedBy("goCoverageSummary", "goCoverageHtml")
    }
}
```

### SIGPIPE Handling

When a Go process runs under Java's `ProcessBuilder`, the stdout pipe can close before the process exits. If Go writes to the closed pipe (e.g. `log.Println` during shutdown), it receives SIGPIPE and terminates immediately. Before the coverage counters are flushed. Add this at the top of `main()`:

```go title="main.go"
func main() {
    signal.Ignore(syscall.SIGPIPE)
    // ...
}
```

This is good practice for any long-running Go service managed by an external process, not just for coverage.

### Running

```bash
# Without coverage (default. zero overhead)
./gradlew e2eTest_sarama

# With coverage. runs tests + generates reports
./gradlew e2eTestWithCoverage -Pgo.coverage=true
```

The HTML report is written to `build/go-coverage/coverage.html`. Container-mode coverage uses the same flag. See [Container Mode](go-container.md#code-coverage).

!!! tip "Why no Stove framework changes were needed"
    Everything is achievable with existing primitives: the `-cover` build flag is a Gradle concern, `GOCOVERDIR` is just another env var, coverage processing happens after tests, and graceful shutdown is handled by the AUT starter (`stove-process` or `stove-container`).

## How Tracing Flows

```
1. StoveKotestExtension starts a TraceContext before each test
2. Stove HTTP client injects `traceparent` header into requests
3. otelhttp middleware extracts traceparent, creates HTTP span as child
4. Handler passes r.Context() to DB functions
5. otelsql creates DB spans as children of the HTTP span
6. All spans share the same trace ID as the test
7. Spans are exported via OTLP gRPC to Stove's receiver
8. tracing { shouldContainSpan(...) } queries spans by trace ID
```

## Running

```bash
# From the go-showcase directory. runs all three Kafka libraries
cd recipes/process/golang/go-showcase
./gradlew e2eTest

./gradlew e2eTest_sarama
./gradlew e2eTest_franz
./gradlew e2eTest_segmentio

./gradlew e2eTestWithCoverage -Pgo.coverage=true
```

## Go Dependencies

```
go.opentelemetry.io/contrib/instrumentation/net/http/otelhttp  # HTTP middleware
go.opentelemetry.io/otel                                        # OTel API
go.opentelemetry.io/otel/exporters/otlp/otlptrace/otlptracegrpc # OTLP gRPC exporter
go.opentelemetry.io/otel/sdk                                    # OTel SDK
github.com/XSAM/otelsql                                         # database/sql auto-instrumentation
github.com/lib/pq                                                # PostgreSQL driver
google.golang.org/grpc                                           # gRPC (for OTLP + bridge)

# Kafka. pick one client + its bridge subpackage:
github.com/IBM/sarama                                            # + stove-kafka/sarama
github.com/twmb/franz-go/pkg/kgo                                 # + stove-kafka/franz
github.com/segmentio/kafka-go                                    # + stove-kafka/segmentio
github.com/trendyol/stove/go/stove-kafka                        # Core bridge (always needed)
```
