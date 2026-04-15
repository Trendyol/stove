# Go Application Setup with Stove

Complete guide for testing Go applications with Stove. Covers HTTP, PostgreSQL, Kafka (with bridge), OpenTelemetry tracing, and dashboard.

## Setup Checklist

```
- [ ] Step 1: Create Go app with env var config + health endpoint + SIGTERM handling
- [ ] Step 2: Add OpenTelemetry instrumentation (otelhttp, otelsql)
- [ ] Step 3: Add Kafka with Stove bridge interceptors (optional)
- [ ] Step 4: Add stove-process dependency (provides goApp() DSL)
- [ ] Step 5: Create test-e2e source set + StoveConfig
- [ ] Step 6: Configure Gradle build (go build + e2eTest)
- [ ] Step 7: Write tests
```

## Step 1: Go app requirements

The Go app must:
- Read config from **environment variables**
- Expose **GET /health** returning 200
- Handle **SIGTERM** for graceful shutdown

Key env vars:

| Variable | Purpose |
|----------|---------|
| `APP_PORT` | HTTP listen port |
| `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USER`, `DB_PASS` | PostgreSQL |
| `OTEL_EXPORTER_OTLP_ENDPOINT` | OTLP gRPC endpoint |
| `KAFKA_BROKERS` | Kafka broker addresses |
| `KAFKA_LIBRARY` | Kafka client: `sarama`, `franz`, or `segmentio` (default: `sarama`) |
| `STOVE_KAFKA_BRIDGE_PORT` | Stove bridge gRPC port (test-only) |

## Step 2: OpenTelemetry

```go
// HTTP: wrap mux with otelhttp
handler := otelhttp.NewHandler(mux, "http.request")

// DB: use otelsql instead of database/sql
db, _ := otelsql.Open("postgres", connStr, otelsql.WithAttributes(semconv.DBSystemPostgreSQL))

// Tracing: use WithSyncer for tests (not WithBatcher)
tp := sdktrace.NewTracerProvider(sdktrace.WithSyncer(exporter), ...)

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

**IBM/sarama:**

```go
import (
    stovekafka "github.com/trendyol/stove/go/stove-kafka"
    stovesarama "github.com/trendyol/stove/go/stove-kafka/sarama"
)

bridge, _ := stovekafka.NewBridgeFromEnv()
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
)

bridge, _ := stovekafka.NewBridgeFromEnv()
defer bridge.Close()

client, _ := kgo.NewClient(
    kgo.SeedBrokers("localhost:9092"),
    kgo.WithHooks(&franz.Hook{Bridge: bridge}),
)
```

**segmentio/kafka-go:**

```go
import (
    stovekafka "github.com/trendyol/stove/go/stove-kafka"
    "github.com/trendyol/stove/go/stove-kafka/segmentio"
)

bridge, _ := stovekafka.NewBridgeFromEnv()
defer bridge.Close()

// After producing
_ = writer.WriteMessages(ctx, msgs...)
segmentio.ReportWritten(ctx, bridge, msgs...)

// After consuming
msg, _ := reader.ReadMessage(ctx)
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

### How it works

- All subpackages convert client-specific types to core `PublishedMessage`/`ConsumedMessage` and call bridge methods
- Consumer interceptors/helpers pre-report commit at `offset+1` (needed for `shouldBeConsumed`)
- All Bridge methods are nil-safe: `(*Bridge)(nil).ReportPublished(...)` is a no-op
- All interceptors/hooks/helpers check for nil bridge first — zero overhead in production

### Test-friendly Kafka settings (Go side)

When running against Testcontainers (Stove e2e tests), configure Kafka clients for **fast feedback**. Default production settings (large batches, long commit intervals, no auto-topic creation) cause timeouts, missed messages, and flaky tests.

**Key principles:**

1. **Auto-create topics** — test containers may not have topics pre-created; without this, produces fail silently or block
2. **Small batch size / low batch timeout** — flush produces immediately so `shouldBePublished` sees them
3. **Short auto-commit interval** — make consumed offsets visible to Stove bridge quickly so `shouldBeConsumed` passes
4. **Unique consumer groups per test run** — prevent offset carryover between runs (e.g. `"myapp-" + library`)

**IBM/sarama:**

```go
config := sarama.NewConfig()
config.Producer.Return.Successes = true
config.Consumer.Offsets.Initial = sarama.OffsetOldest
config.Consumer.Offsets.AutoCommit.Interval = 100 * time.Millisecond
// sarama relies on broker-side auto.create.topics.enable (no client-side setting)
```

**twmb/franz-go:**

```go
client, _ := kgo.NewClient(
    kgo.SeedBrokers(brokerList...),
    kgo.AllowAutoTopicCreation(),                    // client-side topic creation
    kgo.AutoCommitInterval(100 * time.Millisecond),  // fast offset commits
    kgo.ConsumeResetOffset(kgo.NewOffset().AtStart()),
    kgo.WithHooks(&franz.Hook{Bridge: bridge}),
)
```

**segmentio/kafka-go:**

```go
// Writer — flush immediately, auto-create topics
writer := &kafka.Writer{
    Addr:                   kafka.TCP(brokerList...),
    BatchSize:              1,
    BatchTimeout:           10 * time.Millisecond,
    RequiredAcks:           kafka.RequireAll,
    AllowAutoTopicCreation: true,
}

// Reader — fast commits, low wait
reader := kafka.NewReader(kafka.ReaderConfig{
    Brokers:        brokerList,
    GroupID:         groupID,
    Topic:           topic,
    MinBytes:        1,
    MaxBytes:        10e6,
    CommitInterval:  100 * time.Millisecond,
    MaxWait:         500 * time.Millisecond,
})
```

**franz-go: separate producer and consumer clients.** Using a single `kgo.Client` for both produce and consume causes consumer group coordination to block `ProduceSync`, leading to 10-30s delays. Always create two clients:

```go
// Producer — no consumer group overhead
producerClient, _ := kgo.NewClient(
    kgo.SeedBrokers(brokerList...),
    kgo.AllowAutoTopicCreation(),
    kgo.WithHooks(hook),
)

// Consumer — consumer group coordination won't block produces
consumerClient, _ := kgo.NewClient(
    kgo.SeedBrokers(brokerList...),
    kgo.ConsumeTopics(topic),
    kgo.ConsumerGroup(groupID),
    kgo.ConsumeResetOffset(kgo.NewOffset().AtStart()),
    kgo.AutoCommitInterval(100 * time.Millisecond),
    kgo.AllowAutoTopicCreation(),
    kgo.WithHooks(hook),
)
```

**Common pitfall — consumer group offset carryover:** If running the same tests against multiple Kafka libraries sequentially (e.g. sarama → franz → segmentio), use a unique consumer group per library. Otherwise the second run sees committed offsets from the first and skips messages:

```go
groupID := "myapp-" + library  // e.g. "myapp-sarama", "myapp-franz"
```

## Step 4: Add stove-process dependency

The `stove-process` module provides `goApp()` out of the box — no custom `ApplicationUnderTest` needed. It supports passing configs as environment variables (`envMapper`) or CLI arguments (`argsMapper`). Go apps typically use env vars.

```kotlin
dependencies {
    testImplementation(stoveLibs.stoveProcess) // or "com.trendyol:stove-process"
}
```

Source: `starters/process/stove-process/`

## Step 5: StoveConfig

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
            env("OTEL_EXPORTER_OTLP_ENDPOINT", "localhost:$OTLP_PORT")
            env("KAFKA_LIBRARY") { System.getProperty("kafka.library") ?: "sarama" }
            env("STOVE_KAFKA_BRIDGE_PORT", stoveKafkaBridgePortDefault)
        }
    )
}.run()
```

## Step 6: Gradle

```kotlin
val goBinary = layout.buildDirectory.file("go-app").get().asFile

tasks.register<Exec>("buildGoApp") {
    commandLine("go", "build", "-o", goBinary.absolutePath, ".")
    inputs.files(fileTree(".") { include("*.go", "go.mod", "go.sum") })
    outputs.file(goBinary)
}

// Per-library e2e test tasks — each passes KAFKA_LIBRARY to the Go app
val kafkaLibraries = listOf("sarama", "franz", "segmentio")
val kafkaE2eTasks = kafkaLibraries.mapIndexed { index, lib ->
    tasks.register<Test>("e2eTest_$lib") {
        dependsOn("buildGoApp")
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

            postgresql {
                shouldQuery<ProductRow>(
                    query = "SELECT id, name, price FROM products WHERE id = '$productId'",
                    mapper = { row -> ProductRow(row.string("id"), row.string("name"), row.double("price")) }
                ) { rows -> rows.first().name shouldBe "Updated" }
            }
        }
    }
})
```

## Running

```bash
# From the go-showcase directory — runs all three Kafka libraries
cd recipes/process/golang/go-showcase
./gradlew e2eTest

# Run a specific library only
./gradlew e2eTest_sarama
./gradlew e2eTest_franz
./gradlew e2eTest_segmentio
```

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
- Full working example: `recipes/process/golang/go-showcase/`
- Bridge library source: `go/stove-kafka/`
- Docs: `docs/other-languages/go.md`
