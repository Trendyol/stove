# Go · Process Mode

Run your Go binary as the AUT. `stove-process` + `goApp()`. Fastest iteration: no image build, no registry, just `go build` and run.

For CI-grade image parity, see [Container Mode](go-container.md).

<div class="stove-tldr" markdown>
<span class="stove-tldr-title">In 30 seconds</span>
Build your Go binary with Gradle. Stove starts it via <code>goApp()</code>, hands it Postgres + Kafka URLs and the OTLP tracing endpoint as env vars. Write tests with the standard <code>stove { http { } postgresql { } kafka { } tracing { } }</code> DSL. Kafka assertions need the <a href="https://github.com/trendyol/stove/tree/main/go/stove-kafka"><code>stove-kafka</code></a> bridge wired into your producer/consumer.
</div>

A full working example (HTTP + Postgres + Kafka + tracing + coverage) lives at [`recipes/process/golang/go-showcase`](https://github.com/Trendyol/stove/tree/main/recipes/process/golang/go-showcase).

## What your Go app needs

Three things keep the app testable without changing its production startup path:

1. **Env-driven config.** Read connection details from env vars (your test wires them).
2. **OpenTelemetry init.** Standard SDK setup. Reads `OTEL_EXPORTER_OTLP_ENDPOINT`. Disabled in prod if unset.
3. **Optional Stove Kafka bridge** if you want `shouldBePublished` / `shouldBeConsumed` against app-side Kafka activity. Keep it dormant when the bridge env var is absent.

### Anatomy of `main()`

<div class="stove-anatomy" markdown="0">
  <div class="stove-anatomy-code">
func main() {
    signal.Ignore(syscall.SIGPIPE) <span class="anchor">1</span>

    port := getEnv("APP_PORT", "8080") <span class="anchor">2</span>

    shutdownTracing, _ := initTracing(ctx, "my-service") <span class="anchor">3</span>
    defer shutdownTracing(ctx)

    db, _ := initDB(connStr) <span class="anchor">4</span>
    defer db.Close()

    bridge, _ := stovekafka.NewBridgeFromEnv() <span class="anchor">5</span>
    defer bridge.Close()

    producer, stopKafka, _ := initKafka(brokers, bridge)
    defer stopKafka()

    handler := otelhttp.NewHandler(mux, "http.request") <span class="anchor">6</span>
    server := &http.Server{Addr: ":" + port, Handler: handler}
}
  </div>
  <div class="stove-anatomy-notes">
    <div class="stove-note"><span class="stove-note-tag">1</span><strong>Ignore SIGPIPE</strong>. Stove sends SIGTERM to stop the process; SIGPIPE on a closed stdout pipe would kill Go before graceful shutdown finishes.</div>
    <div class="stove-note"><span class="stove-note-tag">2</span><strong>Env-driven config.</strong> Stove's <code>envMapper</code> populates these before start.</div>
    <div class="stove-note"><span class="stove-note-tag">3</span><strong>OTel init</strong> reads <code>OTEL_EXPORTER_OTLP_ENDPOINT</code>. Production: unset → no tracing. Test: set by Stove → spans flow to dashboard.</div>
    <div class="stove-note"><span class="stove-note-tag">4</span><strong><code>otelsql</code></strong> wraps <code>database/sql</code>. DB spans show up when your app routes DB calls through the wrapped driver.</div>
    <div class="stove-note"><span class="stove-note-tag">5</span><strong>Kafka bridge.</strong> Returns nil when <code>STOVE_KAFKA_BRIDGE_PORT</code> isn't set. Make bridge calls nil-safe so production runs do not report to Stove.</div>
    <div class="stove-note"><span class="stove-note-tag">6</span><strong><code>otelhttp</code></strong> extracts <code>traceparent</code> from incoming requests. Go spans tie to the test's trace ID.</div>
  </div>
</div>

### Env vars Stove passes you

| Variable | Use |
|---|---|
| `APP_PORT` | HTTP listen port |
| `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USER`, `DB_PASS` | Postgres connection (rename to whatever you read) |
| `KAFKA_BROKERS` | Kafka bootstrap servers |
| `OTEL_EXPORTER_OTLP_ENDPOINT` | OTLP gRPC endpoint for traces |
| `STOVE_KAFKA_BRIDGE_PORT` | Stove Kafka observer port (test-only) |
| `GOCOVERDIR` | Integration coverage data dir (test-only, when coverage enabled) |

Names are conventions. Map any key your app uses through `envMapper`.

### Tracing essentials

!!! tip "Sync exporter for tests"
    Use `sdktrace.WithSyncer(exporter)` so spans export immediately when they end. Production: `WithBatcher` for performance. The 5-second batch default breaks test assertions.

!!! info "W3C propagation matters"
    Install `propagation.TraceContext{}`. Stove's HTTP client sends a `traceparent` header; `otelhttp` extracts it; every Go span shares the test's trace ID. Dashboard + MCP then correlate them with the failure.

## Kafka: the `stove-kafka` bridge

Stove can only assert on Kafka messages it can see. The bridge forwards your producer/consumer events to Stove over gRPC.

```bash
go get github.com/trendyol/stove/go/stove-kafka
```

```go
import stovekafka "github.com/trendyol/stove/go/stove-kafka"

bridge, _ := stovekafka.NewBridgeFromEnv()
defer bridge.Close()
```

Wire into your Kafka client (one of the three supported; see [stove-kafka](https://github.com/trendyol/stove/tree/main/go/stove-kafka) for the full reference).

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

Using a different Kafka client (confluent-kafka-go, etc.)? Import only the core bridge and call `bridge.ReportPublished()`, `ReportConsumed()`, `ReportCommitted()` directly.

### Test-friendly Kafka settings

Default client settings are tuned for throughput, not test speed. Without overrides, assertions flake. Use small batches + short commit intervals + auto-topic-create.

=== "IBM/sarama"

    ```go
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

!!! warning "Test vs production settings"
    These are aggressive for fast feedback. Production: larger batches, longer commits, broker-managed topic creation.

## Stove test setup

### Gradle

```kotlin title="build.gradle.kts"
val goBinary = layout.buildDirectory.file("go-app").get().asFile
val goExecutable = providers.environmentVariable("GO_EXECUTABLE").getOrElse("go")

tasks.register<Exec>("buildGoApp") {
    description = "Compiles the Go application."
    group = "build"
    commandLine(goExecutable, "build", "-o", goBinary.absolutePath, ".")
    inputs.files(fileTree(".") { include("*.go", "go.mod", "go.sum") })
    outputs.file(goBinary)
}

tasks.register<Test>("e2eTest") {
    description = "Runs Stove e2e tests against the Go binary."
    group = "verification"
    dependsOn("buildGoApp")
    useJUnitPlatform()
    systemProperty("go.app.binary", goBinary.absolutePath)
}

dependencies {
    testImplementation(platform("com.trendyol:stove-bom:$stoveVersion"))
    testImplementation("com.trendyol:stove")
    testImplementation("com.trendyol:stove-process")
    testImplementation("com.trendyol:stove-extensions-kotest")
    testImplementation("com.trendyol:stove-http")
    testImplementation("com.trendyol:stove-postgres")
    testImplementation("com.trendyol:stove-kafka")
    testImplementation("com.trendyol:stove-tracing")
    testImplementation("com.trendyol:stove-dashboard")
}
```

### `StoveConfig.kt`

```kotlin title="StoveConfig.kt"
Stove().with {
    httpClient {
        HttpClientSystemOptions(baseUrl = "http://localhost:8090")
    }

    tracing { enableSpanReceiver() }
    dashboard { DashboardSystemOptions(appName = "my-service") }

    kafka {
        KafkaSystemOptions(
            configureExposedConfiguration = { cfg ->
                listOf("kafka.bootstrapServers=${cfg.bootstrapServers}")
            }
        )
    }

    postgresql {
        PostgresqlOptions(
            databaseName = "appdb",
            configureExposedConfiguration = { cfg ->
                listOf(
                    "database.host=${cfg.host}",
                    "database.port=${cfg.port}",
                    "database.name=${cfg.database}",
                    "database.username=${cfg.username}",
                    "database.password=${cfg.password}"
                )
            }
        )
    }

    goApp(
        target = ProcessTarget.Server(port = 8090, portEnvVar = "APP_PORT"),
        envProvider = envMapper {
            "database.host"          to "DB_HOST"
            "database.port"          to "DB_PORT"
            "database.name"          to "DB_NAME"
            "database.username"      to "DB_USER"
            "database.password"      to "DB_PASS"
            "kafka.bootstrapServers" to "KAFKA_BROKERS"
            env("STOVE_KAFKA_BRIDGE_PORT", stoveKafkaBridgePortDefault)
        }
    )
}.run()
```

`envMapper` declaratively maps Stove's exposed config to env var names your Go app reads. `"stoveKey" to "ENV_VAR"` for config-derived. `env("NAME", "value")` for static. For CLI-arg apps, use `argsMapper` instead (or alongside).

## Writing tests

Same DSL as JVM tests. Bridge (`using<T>`) isn't available because Go runs in a separate process.

```kotlin
class OrderE2ETest : FunSpec({
  test("create product, verify HTTP + DB + Kafka + traces") {
    stove {
      var productId: String? = null

      http {
        postAndExpectBody<ProductResponse>(
          uri = "/api/products",
          body = CreateProductRequest(name = "Test", price = 42.99).some()
        ) {
          it.status shouldBe 201
          productId = it.body().id
        }
      }

      postgresql {
        shouldQuery<ProductRow>(
          query = "SELECT id, name, price FROM products WHERE id = '$productId'",
          mapper = { row -> ProductRow(row.string("id"), row.string("name"), row.double("price")) }
        ) { it.size shouldBe 1 }
      }

      kafka {
        shouldBePublished<ProductCreatedEvent> {
          actual.name == "Test"
        }
      }

      tracing {
        shouldContainSpan("http.request")
        shouldNotHaveFailedSpans()
      }
    }
  }
})
```

To verify the Go app **consumes** events from a topic, publish from the test and assert the resulting state:

```kotlin
test("Go app consumes product update events") {
  stove {
    val productId = "p-${UUID.randomUUID()}"

    kafka {
      publish("product.update", ProductUpdateEvent(id = productId, name = "Updated"))
      shouldBeConsumed<ProductUpdateEvent> { actual.id == productId }
    }

    postgresql {
      shouldQuery<ProductRow>(
        query = "SELECT name FROM products WHERE id = '$productId'",
        mapper = { row -> ProductRow(name = row.string("name")) }
      ) { it.first().name shouldBe "Updated" }
    }
  }
}
```

## Running

```bash
./gradlew e2eTest
```

## Dashboard & MCP

Start `stove` and register `dashboard { }` in `Stove().with`. The Go run then streams system events to `http://localhost:4040`: timeline, traces, snapshots, and Kafka explorer. For AI-assisted triage, agents read the same stored data via the [MCP endpoint](../Components/21-mcp.md).

## How tracing flows

```
1. StoveKotestExtension starts a TraceContext before each test
2. Stove HTTP client injects `traceparent` into requests
3. otelhttp middleware extracts traceparent, creates HTTP span as child
4. Handler passes r.Context() to DB functions
5. otelsql creates DB spans as children of the HTTP span
6. All spans share the test's trace ID
7. Spans export via OTLP gRPC to Stove's receiver
8. tracing { shouldContainSpan(...) } queries by trace ID
```

## <a id="code-coverage"></a>Code coverage (optional)

Stove runs your app outside `go test`, so plain `go test -cover` doesn't apply. Go 1.20+ integration coverage works: build with `go build -cover`, set `GOCOVERDIR`, flush on SIGTERM.

```
1. go build -cover          → instruments the binary
2. GOCOVERDIR=/path         → tells the binary where to write coverage data
3. SIGTERM (Stove stop)     → graceful shutdown triggers coverage flush
4. go tool covdata textfmt  → converts raw data to standard coverage.out
5. go tool cover -func/-html → human-readable reports
```

Opt-in via a Gradle property (no overhead when off):

```kotlin title="build.gradle.kts"
val coverageEnabled = providers.gradleProperty("go.coverage")
    .map { it.toBoolean() }.getOrElse(false)
val goCoverDirPath = layout.buildDirectory.dir("go-coverage").get().asFile.absolutePath

tasks.named<Exec>("buildGoApp") {
    if (coverageEnabled) commandLine(goExecutable, "build", "-cover", "-o", goBinary.absolutePath, ".")
}

tasks.named<Test>("e2eTest") {
    if (coverageEnabled) {
        systemProperty("go.cover.dir", goCoverDirPath)
        outputs.cacheIf { false }
    }
}

if (coverageEnabled) {
    tasks.register<Exec>("goCoverageReport") {
        mustRunAfter("e2eTest")
        commandLine(goExecutable, "tool", "covdata", "textfmt",
            "-i=$goCoverDirPath", "-o=$goCoverOutPath")
    }
    tasks.register<Exec>("goCoverageHtml") {
        dependsOn("goCoverageReport")
        commandLine(goExecutable, "tool", "cover", "-html=$goCoverOutPath", "-o=coverage.html")
    }
}
```

Wire `GOCOVERDIR` through `envMapper`:

```kotlin
env("GOCOVERDIR") {
    System.getProperty("go.cover.dir")?.also { java.io.File(it).mkdirs() } ?: ""
}
```

Run:

```bash
./gradlew e2eTest -Pgo.coverage=true
./gradlew goCoverageHtml -Pgo.coverage=true
# Open build/go-coverage/coverage.html
```

!!! warning "SIGPIPE handling"
    `signal.Ignore(syscall.SIGPIPE)` at the top of `main()` is required. Without it, Go can die on SIGPIPE before coverage counters flush. Same trick helps any long-running Go service managed by an external process.

## Go dependencies

```
go.opentelemetry.io/contrib/instrumentation/net/http/otelhttp  # HTTP middleware
go.opentelemetry.io/otel                                        # OTel API
go.opentelemetry.io/otel/exporters/otlp/otlptrace/otlptracegrpc # OTLP gRPC exporter
go.opentelemetry.io/otel/sdk                                    # OTel SDK
github.com/XSAM/otelsql                                         # database/sql auto-instrumentation
github.com/lib/pq                                                # PostgreSQL driver

# Kafka. Pick one client + its bridge subpackage:
github.com/IBM/sarama                                            # + stove-kafka/sarama
github.com/twmb/franz-go/pkg/kgo                                 # + stove-kafka/franz
github.com/segmentio/kafka-go                                    # + stove-kafka/segmentio
github.com/trendyol/stove/go/stove-kafka                        # core bridge
```

## Pairs well with

- [Go Container Mode](go-container.md) for CI-grade image parity
- [Tracing](../Components/15-tracing.md) for span assertions
- [Dashboard](../Components/18-dashboard.md) for live UI
- [MCP](../Components/21-mcp.md) for agent-driven triage
