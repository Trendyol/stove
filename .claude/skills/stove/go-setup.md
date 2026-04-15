# Go Application Setup with Stove

Complete guide for testing Go applications with Stove. Covers HTTP, PostgreSQL, Kafka (with bridge), OpenTelemetry tracing, and dashboard.

## Setup Checklist

```
- [ ] Step 1: Create Go app with env var config + health endpoint + SIGTERM handling
- [ ] Step 2: Add OpenTelemetry instrumentation (otelhttp, otelsql)
- [ ] Step 3: Add Kafka with Stove bridge interceptors (optional)
- [ ] Step 4: Create GoApplicationUnderTest + goApp() DSL
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

## Step 4: GoApplicationUnderTest

Reference implementation at `recipes/go-recipes/go-showcase/src/test-e2e/kotlin/.../setup/GoApplicationUnderTest.kt`.

Key points:
- Starts Go binary via `ProcessBuilder`
- Passes all config as env vars via `configMapper`
- Reads stdout in a background coroutine (prevents buffer blocking)
- Waits for health endpoint before returning
- Sends SIGTERM on stop, force-kills after 5s timeout

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
        port = APP_PORT,
        configMapper = { configs ->
            val map = configs.associate { it.split("=", limit = 2).let { (k, v) -> k to v } }
            buildMap {
                map["database.host"]?.let { put("DB_HOST", it) }
                map["database.port"]?.let { put("DB_PORT", it) }
                map["database.name"]?.let { put("DB_NAME", it) }
                map["database.username"]?.let { put("DB_USER", it) }
                map["database.password"]?.let { put("DB_PASS", it) }
                put("OTEL_EXPORTER_OTLP_ENDPOINT", "localhost:$OTLP_PORT")
                map["kafka.bootstrapServers"]?.let { put("KAFKA_BROKERS", it) }
                put("STOVE_KAFKA_BRIDGE_PORT", stoveKafkaBridgePortDefault)
            }
        }
    )
}.run()
```

## Step 6: Gradle

```kotlin
val goSourceDir = project.file("product-app-go")
val goBinary = project.layout.buildDirectory.file("go-app").get().asFile

tasks.register<Exec>("buildGoApp") {
    workingDir = goSourceDir
    commandLine("go", "build", "-o", goBinary.absolutePath, ".")
    inputs.files(
        fileTree(goSourceDir) { include("*.go", "go.mod", "go.sum") },
        fileTree(project.rootDir.resolve("go/stove-kafka")) { include("*.go", "go.mod") }
    )
    outputs.file(goBinary)
}

tasks.named<Test>("e2eTest") {
    dependsOn("buildGoApp")
    systemProperty("go.app.binary", goBinary.absolutePath)
}

dependencies {
    testImplementation(stoveLibs.stove)
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
# From the recipes directory
./gradlew go-recipes:go-showcase:e2eTest
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

- Full working example: `recipes/go-recipes/go-showcase/`
- Bridge library source: `go/stove-kafka/`
- Docs: `docs/other-languages/go.md`
