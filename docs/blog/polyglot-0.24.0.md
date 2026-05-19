# Stove 0.24.0 — Going Polyglot, and an MCP for AI Triage

Stove started as a JVM end-to-end testing framework. Spring Boot, Ktor, Quarkus, Micronaut — spin them up with real PostgreSQL, real Kafka, real WireMock, then assert the whole flow with one Kotlin DSL. That core hasn't changed. What 0.24.0 changes is who gets to play.

This release pushes on five things at once: <span data-rn="highlight" data-rn-color="#00968855" data-rn-duration="800">Go becomes a first-class application under test</span>, <span data-rn="underline" data-rn-color="#009688">any container image can be the AUT</span>, the framework starter is no longer required (test against an already-deployed app), one system type can have many keyed instances (verify across services), and the <span data-rn="underline" data-rn-color="#ff9800">`stove` CLI grows an MCP endpoint</span> so AI agents can triage failed runs without scraping logs.

## Why polyglot, why now

Microservice fleets are not monolingual. The order service might be Spring Boot, the inventory service Go, the recommender Python, the edge router Rust. If your e2e framework only covers the JVM, every non-JVM service either gets its own bespoke harness or doesn't get end-to-end tested at all. Both are bad outcomes.

The interesting question isn't "how do we add a Go runner?" It's "what's actually language-specific about an end-to-end test?" The answer turns out to be: very little. The Stove DSL — `http {}`, `postgresql {}`, `kafka {}`, `tracing {}`, `dashboard {}` — is about the *contract*: what went over the wire, what's in the database, what spans appeared. The language of the application under test is an implementation detail.

So 0.24.0 splits AUT lifecycle from test logic. Two new starters, the test surface unchanged:

- **`stove-process`** runs your app as a host binary. Fast iteration, easy debugging.
- **`stove-container`** runs your app as a Docker image. CI parity with the artifact you ship.

Both work for any language. Both pass infrastructure config the same way (`envMapper` / `argsMapper`). Both ride the same readiness model. The Kotlin tests don't care which one is in play.

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
        insertProduct(r.Context(), db, product)  // otelsql traces this automatically

        if producer != nil {
            event := ProductCreatedEvent{ID: product.ID, Name: product.Name, Price: product.Price}
            eventBytes, _ := json.Marshal(event)
            producer.SendMessage("product.created", product.ID, eventBytes)
        }
        writeJSON(w, http.StatusCreated, product)
    }
}
```

The Stove HTTP client sends a `traceparent` header. `otelhttp` extracts it. Spans created in the Go app share the originating test's trace ID. No glue code, no manual correlation.

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

If you removed the file path, you couldn't tell from this test that the AUT is in Go. That's the point.

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

In production, `STOVE_KAFKA_BRIDGE_PORT` is unset, `NewBridgeFromEnv()` returns nil, and every method becomes a no-op. **Zero overhead in prod, full assertion fidelity in tests.**

### Coverage from black-box tests

A nice side-effect of standardizing on `stove-process` and `stove-container`: Go 1.20+ integration coverage just works. Build with `go build -cover`, set `GOCOVERDIR`, and Go writes coverage data on graceful shutdown. Stove already sends SIGTERM and waits for clean exit — exactly the lifecycle Go's coverage tooling expects.

```bash
./gradlew e2eTestWithCoverage -Pgo.coverage=true
./gradlew e2eTest-containerWithCoverage -Pgo.coverage=true
```

Per-function summary, HTML report. One catch worth a paragraph: when Go runs under Java's `ProcessBuilder`, the stdout pipe can close before the process exits. Log writes to that closed pipe trigger SIGPIPE — Go dies before flushing coverage. The fix is one line in `main()`:

```go
signal.Ignore(syscall.SIGPIPE)
```

That's it. No framework changes were needed for coverage; it's a Gradle concern, an env var, and an existing graceful-shutdown signal.

## Container mode is not just for Go

`stove-container` is **language-agnostic**. Anything that ships in an image works — Go, Python, Node.js, Rust, .NET, even your existing JVM artifact when you want to test the actual deployed binary instead of the in-process bean graph.

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

A common pattern: `e2eTest` runs process mode for daily local development; `e2eTest-container` runs container mode in CI against the image the build job just published. Same StoveConfig, same tests, branched on a system property.

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

Same Stove DSL. Same assertions. No `springBoot()` / `ktor()` / `goApp()` block. Stove waits for the deployed health check, then runs your tests against the live URL — and verifies side effects in the actual database / Kafka / Redis the deployed app uses (via `*.provided(...)` factories on each system).

The use case is post-deployment smoke testing: the e2e tests you already wrote can double as a CI/CD gate that hits staging immediately after a release. Same code, same intent, different infrastructure.

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

This pairs naturally with `providedApplication()`. A single Stove config can wire your app's API, three downstream services, two shared databases, and a Kafka cluster — all already running in staging — and a single Kotlin test asserts behaviour across all of them.

## MCP — failure triage for AI agents

The other big addition in 0.24.0 has nothing to do with non-JVM apps and everything to do with how people debug failed e2e runs in 2026.

If you're using an AI agent in your editor or CI bot, you've probably watched it try to triage a failed test by reading the entire stdout, then the entire stderr, then `tail`-ing logs, then guessing at trace IDs. It works, but it burns tokens proportional to log size, and it hallucinates when names are ambiguous.

The Stove dashboard already records every run — timeline, traces, snapshots, Kafka message counts — in a local SQLite database. 0.24.0 adds a [Model Context Protocol](https://modelcontextprotocol.io/) endpoint on the same `stove` CLI that exposes that data as structured tools:

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

2. **Loopback only.** The `/mcp` endpoint accepts only localhost `Host`/`Origin` headers and rejects anything else. This blocks DNS rebinding from a malicious page in your browser. Safe to leave running on a dev machine; not exposed externally.

If MCP is unavailable, agents fall back to normal test output and logs — it's an optimization, not a dependency.

## Putting it together

Stove 0.24.0 is one consistent picture, even though the changes touch four different surfaces:

- A test that drives a Go service through HTTP, asserts on PostgreSQL state, validates Kafka messages, and traces the call chain — using the exact same DSL that drives the Spring Boot service next door.
- The same test running against a host binary in your IDE for fast feedback, then against a real Docker image in CI for production parity, with one `-Daut.mode` flip.
- When something fails, the dashboard shows you what happened. When the agent in your editor wants to help, it asks the dashboard via MCP instead of inhaling logs.

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
