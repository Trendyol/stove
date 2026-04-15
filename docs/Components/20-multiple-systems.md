# <span data-rn="underline" data-rn-color="#ff9800">Multiple Systems</span>

By default, Stove registers one instance per system type --- one PostgreSQL, one Kafka, one HTTP client. With multiple systems, you can register <span data-rn="highlight" data-rn-color="#00968855" data-rn-duration="800">multiple instances of the same system type</span>, each identified by a typed key.

## When to Use

- **Microservice integration** --- call multiple services, each with its own HTTP client or gRPC stub
- **Multiple databases** --- verify state in separate PostgreSQL or MongoDB instances
- **Multi-cluster Kafka** --- publish/consume from different Kafka clusters
- **Cross-service verification** --- after calling your app, check that dependent services received the right data

## Define Keys

Keys are Kotlin singleton objects implementing `SystemKey`:

```kotlin
object OrderService : SystemKey
object PaymentService : SystemKey
object AppDb : SystemKey
object AnalyticsDb : SystemKey
```

!!! tip "Why Objects Instead of Strings?"
    Kotlin objects give you compile-time safety, IDE autocomplete, and refactor-safe references. Typos become compile errors. The same key can be reused across protocols --- `httpClient(PaymentService)` and `grpc(PaymentService)` both refer to the same logical service.

## Configure

Pass the key as the first argument to any system DSL function:

```kotlin hl_lines="3 8 13 18"
Stove().with {
    // Default HTTP client --- your app
    httpClient {
        HttpClientSystemOptions(baseUrl = "https://myapp.staging.com")
    }

    // Keyed HTTP clients --- dependent services
    httpClient(OrderService) {
        HttpClientSystemOptions(baseUrl = "https://order.internal.com")
    }
    httpClient(PaymentService) {
        HttpClientSystemOptions(baseUrl = "https://payment.internal.com")
    }

    // Keyed databases
    postgresql(AppDb) {
        PostgresqlOptions.provided(
            jdbcUrl = "jdbc:postgresql://staging-db:5432/myapp",
            host = "staging-db", port = 5432,
            configureExposedConfiguration = { emptyList() }
        )
    }
    postgresql(AnalyticsDb) {
        PostgresqlOptions.provided(
            jdbcUrl = "jdbc:postgresql://analytics-db:5432/analytics",
            host = "analytics-db", port = 5432,
            configureExposedConfiguration = { emptyList() }
        )
    }

    providedApplication()
}.run()
```

## Write Tests

Pass the key to the validation DSL:

```kotlin hl_lines="5 12 19 26"
test("create order, verify across services and databases") {
    stove {
        // Call the app (default HTTP --- no key)
        http {
            postAndExpectJson<OrderResponse>("/orders", body = request.some()) { order ->
                order.id shouldNotBe null
            }
        }

        // Verify order service received the order
        http(OrderService) {
            getResponse("/api/orders/$orderId") { resp ->
                resp.status shouldBe 200
            }
        }

        // Verify payment was processed
        http(PaymentService) {
            getResponse("/api/payments?orderId=$orderId") { resp ->
                resp.status shouldBe 200
            }
        }

        // Verify in app's database
        postgresql(AppDb) {
            shouldQuery<Order>("SELECT * FROM orders WHERE id = ?", listOf(orderId)) { rows ->
                rows shouldHaveSize 1
            }
        }

        // Verify analytics event landed
        postgresql(AnalyticsDb) {
            shouldQuery<AnalyticsEvent>("SELECT * FROM events WHERE order_id = ?", listOf(orderId)) { events ->
                events.first().type shouldBe "ORDER_CREATED"
            }
        }
    }
}
```

## Supported Systems

All multi-instance dependency systems support keyed registration:

| Category | Systems |
|----------|---------|
| **Databases** | PostgreSQL, MySQL, MSSQL, MongoDB, Cassandra, Couchbase, Redis, Elasticsearch |
| **Protocol clients** | HTTP Client, gRPC |
| **Messaging** | Kafka |
| **Mocking** | WireMock, gRPC Mock |

Single-instance systems (Bridge, Tracing, Dashboard) and framework starters (`springBoot()`, `ktor()`) do **not** support keyed registration --- there is only one application under test.

!!! info "Spring Kafka"
    The Spring Kafka starter (`stove-spring-kafka`) does not support keyed instances because it is tied to a single Spring application context. Use the standalone `stove-kafka` module if you need multiple Kafka instances.

## Default and Keyed Coexist

Default (unkeyed) and keyed instances of the same type are independent:

```kotlin
// Registration
httpClient { HttpClientSystemOptions(baseUrl = "https://myapp.com") }      // default
httpClient(OrderService) { HttpClientSystemOptions(baseUrl = "https://order.internal.com") }  // keyed

// Validation
http { /* hits myapp.com */ }              // default
http(OrderService) { /* hits order.internal.com */ }  // keyed
```

## Reporting

Keyed systems produce distinguishable names in reports and traces:

```
HTTP > GET /orders                          # default
HTTP [OrderService] > GET /api/orders/123   # keyed
HTTP [PaymentService] > GET /api/payments   # keyed
PostgreSQL [AppDb] > shouldQuery > SELECT   # keyed
PostgreSQL [AnalyticsDb] > shouldQuery      # keyed
```

## Error Handling

If you pass a key that wasn't registered, you get a clear runtime error:

```
SystemNotRegisteredException: HttpSystem was not registered.
No HttpSystem registered with key 'OrderService'
```

## Combining with Provided Application

Keyed systems and `providedApplication()` are designed to work together for full black-box testing:

```kotlin hl_lines="2-4 7-14 17"
Stove().with {
    // Your app's API
    httpClient { HttpClientSystemOptions(baseUrl = "https://staging.myapp.com") }

    // Dependent services and infrastructure
    httpClient(OrderService) { HttpClientSystemOptions(baseUrl = "https://order.internal.com") }
    postgresql(AppDb) {
        PostgresqlOptions.provided(
            jdbcUrl = "jdbc:postgresql://staging-db:5432/myapp",
            host = "staging-db", port = 5432,
            configureExposedConfiguration = { emptyList() }
        )
    }
    kafka {
        KafkaSystemOptions.provided(
            bootstrapServers = "staging-kafka:9092",
            configureExposedConfiguration = { emptyList() }
        )
    }

    // App already running
    providedApplication {
        ProvidedApplicationOptions(
            readiness = ReadinessStrategy.HttpGet(url = "https://staging.myapp.com/health")
        )
    }
}.run()
```

See also: [Provided Application](19-provided-application.md) for testing against deployed apps.
