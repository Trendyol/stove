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
  bridge.go           # Core: Bridge, PublishedMessage, ConsumedMessage (no Kafka client dependency)
  sarama/             # IBM/sarama interceptors
    interceptors.go   # ProducerInterceptor, ConsumerInterceptor
  stoveobserver/      # Generated gRPC code
```

### Add the dependency

```bash
go get github.com/trendyol/stove/go/stove-kafka
```

### Initialize bridge + wire interceptors

```go
import (
    stovekafka "github.com/trendyol/stove/go/stove-kafka"
    stovesarama "github.com/trendyol/stove/go/stove-kafka/sarama"
)

// Bridge is nil when STOVE_KAFKA_BRIDGE_PORT is not set (production) — zero overhead
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

### How it works

- `ProducerInterceptor.OnSend()` calls `bridge.ReportPublished()` -> gRPC -> Stove observer -> `shouldBePublished` works
- `ConsumerInterceptor.OnConsume()` calls `bridge.ReportConsumed()` + `bridge.ReportCommitted(offset+1)` -> gRPC -> Stove observer -> `shouldBeConsumed` works
- Sarama lacks `onCommit()`, so the consumer interceptor pre-reports commit at `offset+1`
- All Bridge methods are nil-safe: `(*Bridge)(nil).ReportPublished(...)` is a no-op

### Adding support for other Go Kafka libraries

The core bridge (`PublishedMessage`, `ConsumedMessage`, `Bridge`) is library-agnostic. To add a new client library:

1. Create a new subpackage (e.g., `go/stove-kafka/franz/`)
2. Implement interceptors/hooks that convert library-specific types to `stovekafka.PublishedMessage` / `stovekafka.ConsumedMessage`
3. Call `bridge.ReportPublished()`, `bridge.ReportConsumed()`, `bridge.ReportCommitted()`

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
github.com/IBM/sarama                                            # Kafka client
github.com/trendyol/stove/go/stove-kafka                        # Stove Kafka bridge (core)
github.com/trendyol/stove/go/stove-kafka/sarama                 # Sarama interceptors
github.com/XSAM/otelsql                                         # database/sql instrumentation
go.opentelemetry.io/contrib/instrumentation/net/http/otelhttp    # HTTP instrumentation
go.opentelemetry.io/otel/exporters/otlp/otlptrace/otlptracegrpc # OTLP exporter
google.golang.org/grpc                                           # gRPC
```

## Reference

- Full working example: `recipes/go-recipes/go-showcase/`
- Bridge library source: `go/stove-kafka/`
- Docs: `docs/other-languages/go.md`
