# Stove 0.24.0 — Going Polyglot, and an MCP for AI Triage

Stove started as a JVM end-to-end testing framework. Spring Boot, Ktor, Quarkus, Micronaut — start the application under test, start real PostgreSQL, Kafka, WireMock, and other systems, then assert the flow with one Kotlin test DSL. That core stays in place. What 0.24.0 changes is how the application under test can be started or targeted.

This release pushes on five things at once: <span data-rn="highlight" data-rn-color="#00968855" data-rn-duration="800">Go can be a first-class application under test</span>, <span data-rn="underline" data-rn-color="#009688">container images can be targeted as AUTs</span>, the framework runner is no longer required for already-running apps, one system type can have many keyed instances, and the <span data-rn="underline" data-rn-color="#ff9800">`stove` CLI grows an MCP endpoint</span> so AI agents can inspect failed runs through structured data instead of scraping logs.

## Why polyglot, why now

Microservice fleets are not monolingual. The order service might be Spring Boot, the inventory service Go, the recommender Python, the edge router Rust. If your e2e framework only covers the JVM, every non-JVM service either gets its own bespoke harness or doesn't get end-to-end tested at all. Both are bad outcomes.

The interesting question isn't "how do we add a Go runner?" It's "what's actually language-specific about an end-to-end test?" A lot of the test surface is not language-specific at all. The Stove DSL — `http {}`, `postgresql {}`, `kafka {}`, `tracing {}`, `dashboard {}` — is about the external contract: what went over the wire, what's in the database, what messages were observed, what spans were exported. The language matters at the runner and instrumentation boundary.

So 0.24.0 splits AUT lifecycle from test logic. Two new AUT runners handle the process/container side while the assertion surface stays close to the JVM starters:

- **`stove-process`** runs your app as a host binary. Fast iteration, easy debugging.
- **`stove-container`** runs your app as a Docker image. CI parity with the artifact you ship.

Both can target any language that can read mapped configuration, expose a readiness signal, and connect to the infrastructure Stove started. `envMapper` and `argsMapper` pass system-derived values into the AUT, and the same readiness model decides when tests can start.

## A tour: Go on Stove

Go gets the deepest treatment because it's the showcase language. The [`go-showcase`](https://github.com/Trendyol/stove/tree/main/recipes/process/golang/go-showcase) recipe is an HTTP + PostgreSQL + Kafka service. Same `StoveConfig.kt` runs the binary directly *or* runs it inside a Docker container, branched on a single system property.

### The Go side

The Go application stays small. All tracing is in the infrastructure layer — `otelhttp` wraps the mux, `otelsql` wraps the DB driver. Business handlers stay clean:

```go title="handlers.go"
func handleCreateProduct(db *sql.DB, producer KafkaProducer) http.HandlerFunc {
    return func(w http.ResponseWriter, r *http.Request) {
        var req createProductRequest
        json.NewDecoder(r.Body).Decode(&req)

        product := Product{ID: uuid.New().String(), Name: req.Name, Price: req.Price}
        insertProduct(r.Context(), db, product)  // otelsql emits spans for this call

        if producer != nil {
            event := ProductCreatedEvent{ID: product.ID, Name: product.Name, Price: product.Price}
            eventBytes, _ := json.Marshal(event)
            producer.SendMessage("product.created", product.ID, eventBytes)
        }
        writeJSON(w, http.StatusCreated, product)
    }
}
```

The Stove HTTP client sends a `traceparent` header. `otelhttp` extracts it. Spans created in the Go app share the originating test's trace ID. The Go app still owns its OpenTelemetry SDK setup, but it does not need per-test correlation code.

### The Kotlin side

```kotlin
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
            shouldContainSpan("http.request")
            shouldNotHaveFailedSpans()
        }
    }
}
```

If you removed the file path, you couldn't tell from this test that the AUT is in Go. That's the point: the assertions target external behaviour. The Go-specific work lives in runner configuration, OpenTelemetry setup, and Kafka observation.

### Kafka, in three flavors

`shouldBePublished<T>` and `shouldBeConsumed<T>` need an observation point on the broker side. For JVM apps, Stove uses Kafka client interceptors. For Go, 0.24.0 ships [`stove-kafka`](https://github.com/trendyol/stove/tree/main/go/stove-kafka), a small Go library that forwards produced/consumed/committed messages over gRPC to Stove's observer.

The bridge is library-agnostic at its core. First-party integrations exist for the three Go Kafka clients people actually use:

- [IBM/sarama](https://github.com/IBM/sarama) via `ProducerInterceptor` / `ConsumerInterceptor`
- [twmb/franz-go](https://github.com/twmb/franz-go) via `kgo.WithHooks(...)`
- [segmentio/kafka-go](https://github.com/segmentio/kafka-go) via tiny `ReportWritten` / `ReportRead` helpers

Want confluent-kafka-go or something else? Skip the subpackages, use the core API:

```go
bridge.ReportPublished(ctx, &stovekafka.PublishedMessage{...})
bridge.ReportConsumed(ctx,  &stovekafka.ConsumedMessage{...})
bridge.ReportCommitted(ctx, topic, partition, offset+1)
```

In production, `STOVE_KAFKA_BRIDGE_PORT` is unset, `NewBridgeFromEnv()` returns nil, and every method becomes a no-op. In tests, the same bridge gives Stove the observation point needed for `shouldBePublished<T>` and `shouldBeConsumed<T>`.

### Coverage from process/container tests

A useful side-effect of standardizing on `stove-process` and `stove-container`: Go 1.20+ integration coverage fits this lifecycle. Build with `go build -cover`, set `GOCOVERDIR`, and Go writes coverage data on graceful shutdown. Stove sends SIGTERM and waits for clean exit, which is the shutdown path Go's coverage tooling expects.

```bash
./gradlew e2eTestWithCoverage -Pgo.coverage=true
./gradlew e2eTest-containerWithCoverage -Pgo.coverage=true
```

Per-function summary, HTML report. One catch worth a paragraph: when Go runs under Java's `ProcessBuilder`, the stdout pipe can close before the process exits. Log writes to that closed pipe trigger SIGPIPE — Go dies before flushing coverage. The fix is one line in `main()`:

```go
signal.Ignore(syscall.SIGPIPE)
```

That's it. No Stove framework changes were needed for coverage; it's a Gradle concern, an env var, and an existing graceful-shutdown signal.

## Container mode is not just for Go

`stove-container` is **language-agnostic** at the runner boundary. Any image can be a target when it exposes a network surface, has a readiness signal, and can receive mapped configuration — Go, Python, Node.js, Rust, .NET, even your existing JVM artifact when you want to test the deployed binary instead of the in-process bean graph.

One thing worth being explicit about: <span data-rn="highlight" data-rn-color="#ff980055" data-rn-duration="800">building the image is not Stove's job</span>. `containerApp(...)` only needs an image reference. Where it comes from is your call:

- A tag your CI just produced (`-Papp.image=ghcr.io/acme/app:sha-abc`)
- A pull from a registry, lazy on first use
- An optional Gradle `Exec` task that runs `docker build` for local iteration

Most teams already have a perfectly good image-build pipeline. Stove doesn't try to own it.

```kotlin
containerApp(
    image = System.getProperty("app.container.image"),
    target = ContainerTarget.Server(
        hostPort = 8090, internalPort = 8090,
        portEnvVar = "APP_PORT", bindHostPort = false
    ),
    envProvider = envMapper {
        "database.host" to "DB_HOST"
        "kafka.bootstrapServers" to "KAFKA_BROKERS"
        env("OTEL_EXPORTER_OTLP_ENDPOINT", "localhost:4317")
    },
    configureContainer = { withNetworkMode("host") }
)
```

`configureContainer { ... }` exposes the underlying Testcontainers `GenericContainer`, so anything Testcontainers can do — bind mounts, network mode, log consumers, capabilities — is available without bespoke API surface.

A common pattern: `e2eTest` runs process mode for daily local development; `e2eTest-container` runs container mode in CI against the image the build job just published. The assertions can stay the same while the runner block, env mapping, and readiness settings branch on a system property.

## Black-box mode: testing apps Stove didn't start

Polyglot AUT is one half of "Stove doesn't have to own the app." The other half is `providedApplication()` — telling Stove the application is already running somewhere, and you just want to run your tests against it.

```kotlin
Stove().with {
    httpClient { HttpClientSystemOptions(baseUrl = "https://staging.myapp.com") }

    postgresql {
        PostgresqlOptions.provided(
            jdbcUrl = "jdbc:postgresql://staging-db:5432/myapp",
            host = "staging-db", port = 5432,
            configureExposedConfiguration = { emptyList() }
        )
    }

    providedApplication {
        ProvidedApplicationOptions(
            readiness = ReadinessStrategy.HttpGet(url = "https://staging.myapp.com/health")
        )
    }
}.run()
```

The same Stove assertion DSL is available for the external surfaces you configure. There is no `springBoot()` / `ktor()` / `goApp()` block, and Stove does not boot the app or inject properties, env vars, or command-line arguments into it. Stove waits for the deployed health check, then runs your tests against the live URL and any provided infrastructure you explicitly point at with `*.provided(...)` factories.

The use case is post-deployment smoke testing: the e2e tests you already wrote can double as a CI/CD gate that hits staging immediately after a release. The test intent stays the same; the configuration changes from Stove-started infrastructure to externally provided endpoints.

## Multiple instances of the same system, with keys

Microservice integration tests usually need to talk to more than one downstream service, or verify state in more than one database. 0.24.0 adds **keyed system registration** for that:

```kotlin
object OrderService : SystemKey
object PaymentService : SystemKey
object AppDb : SystemKey
object AnalyticsDb : SystemKey

Stove().with {
    httpClient { HttpClientSystemOptions(baseUrl = "https://myapp.com") }                       // default
    httpClient(OrderService) { HttpClientSystemOptions(baseUrl = "https://order.internal") }
    httpClient(PaymentService) { HttpClientSystemOptions(baseUrl = "https://pay.internal") }

    postgresql(AppDb) { /* ... */ }
    postgresql(AnalyticsDb) { /* ... */ }
}.run()
```

In tests, the same key drives the validation DSL:

```kotlin
http(OrderService) { getResponse("/api/orders/$orderId") { /* ... */ } }
postgresql(AnalyticsDb) { shouldQuery<AnalyticsEvent>(/* ... */) { /* ... */ } }
```

Keys are Kotlin `object`s — compile-time-safe, IDE-autocompleted, refactor-safe. Default and keyed instances of the same type coexist independently. Reports and traces label keyed calls (`HTTP [OrderService] > GET /api/orders/123`) so it's clear which service did what.

This pairs naturally with `providedApplication()`. A single Stove config can point at your app's API, three downstream services, two shared databases, and a Kafka cluster — all already running in staging — and a single Kotlin test can assert behaviour across those configured surfaces.

## MCP — failure triage for AI agents

The other big addition in 0.24.0 has nothing to do with non-JVM apps and everything to do with how people debug failed e2e runs in 2026.

If you're using an AI agent in your editor or CI bot, you've probably watched it try to triage a failed test by reading the entire stdout, then the entire stderr, then `tail`-ing logs, then guessing at trace IDs. It works, but it burns tokens proportional to log size, and it hallucinates when names are ambiguous.

When `dashboard { }` is enabled and the CLI is running, Stove records the evidence it receives from registered systems in a local SQLite database: timeline entries, snapshots, traces when tracing is enabled and spans are exported, and Kafka evidence when Kafka observation is configured. 0.24.0 adds a [Model Context Protocol](https://modelcontextprotocol.io/) endpoint on the same `stove` CLI that exposes that data as structured tools:

```text
$ stove
Stove CLI v0.24.0 running
UI:   http://localhost:4040
REST: http://localhost:4040/api/v1
MCP:  http://localhost:4040/mcp
gRPC: localhost:4041
```

Wire any MCP-capable agent at `http://localhost:4040/mcp`. Then the conversation looks like:

```text
Agent: stove_failures()
  → 2 failed runs across go-showcase and order-service
Agent: stove_failure_detail(run_id="...", test_id="...")
  → compact failure packet: assertion, expected vs actual,
    timeline of last 5 actions, exception class
Agent: stove_trace(run_id="...", test_id="...")
  → critical path: 4 spans, exception in PostgresOrderRepository.save
```

Eight tools total, all read-only, all local-only. Defaults are token-aware: payloads are truncated deterministically with omitted-counts, sensitive keys (`authorization`, `cookie`, `password`, `secret`, `token`, `apiKey`, `credential`) are redacted before return, and a `budget: tiny|compact|full` knob lets the agent dial detail when needed.

Two design decisions worth calling out:

1. **`run_id + test_id` is the only authoritative test selector.** Apps and runs can contain duplicate test names; an agent inferring "OrderTest::should create order" from a phrase will eventually hit the wrong run. Every tool result includes the next call's exact arguments — agents follow links, they don't construct queries.

2. **Loopback only.** The `/mcp` endpoint accepts only localhost `Host`/`Origin` headers and rejects anything else. This blocks DNS rebinding from a malicious page in your browser. It is designed for local development, not for exposing as a shared service.

If MCP is unavailable, agents fall back to normal test output and logs — it's an optimization, not a dependency.

## Putting it together

Stove 0.24.0 is one consistent picture, even though the changes touch four different surfaces:

- A test that drives a Go service through HTTP, asserts on PostgreSQL state, validates Kafka messages, and traces the call chain through the same external-surface DSL that drives the Spring Boot service next door.
- The same assertions running against a host binary in your IDE for fast feedback, then against a Docker image in CI, with runner configuration selected by `-Daut.mode`.
- When something fails, the dashboard shows the evidence it received. When the agent in your editor wants to help, it can ask the dashboard via MCP instead of reading unstructured logs first.

Three integrations, one feedback loop. That's the release.

---

## Getting started

Upgrade the CLI:

```bash
brew upgrade stove
```

Add the modules you need to your test classpath:

```kotlin
testImplementation(platform("com.trendyol:stove-bom:0.24.0"))
testImplementation("com.trendyol:stove-process")     // host binary
testImplementation("com.trendyol:stove-container")   // Docker image
testImplementation("com.trendyol:stove-dashboard")   // dashboard streaming
testImplementation("com.trendyol:stove-tracing")     // distributed tracing
testImplementation("com.trendyol:stove-kafka")       // Kafka assertions
```

For Go Kafka assertions:

```bash
go get github.com/trendyol/stove/go/stove-kafka
```

## Links

- [Full 0.24.0 release notes](../release-notes/0.24.0.md)
- [Other Languages & Stacks overview](../other-languages/index.md)
- [Go Process Mode](../other-languages/go-process.md)
- [Go Container Mode](../other-languages/go-container.md)
- [MCP component docs](../Components/21-mcp.md)
- [Dashboard component docs](../Components/18-dashboard.md)
- [`go-showcase` recipe](https://github.com/Trendyol/stove/tree/main/recipes/process/golang/go-showcase) — process *and* container modes in one repo
- [`stove-kafka` Go bridge](https://github.com/Trendyol/stove/tree/main/go/stove-kafka)
