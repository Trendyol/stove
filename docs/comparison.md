---
title: Why Stove? The same test. Less wiring.
description: Compare Spring Boot, Testcontainers, and supporting libraries with Stove for application setup, cross-system assertions, and failure evidence.
hide:
  - navigation
  - toc
---

<div class="stove-comparison" markdown="1">

<div class="comparison-hero" markdown="1">

<p class="comparison-eyebrow">Spring Boot + Testcontainers · A practical comparison</p>

# The same test. Less wiring.

<p class="comparison-lead">Starting containers is one part of an end-to-end test. Stove connects application startup, dependency configuration, assertions, and failure evidence.</p>

<div class="comparison-actions" markdown="1">

[Compare the code](#prepare){ .stove-btn .primary }
[Try this setup](wizard.md?rt=jvm&fw=spring-boot&test=kotest&sys=sys.http,sys.postgresql,sys.kafka&mk=sys.wiremock&obs=obs.reporting&br=0&step=6){ .stove-btn }

</div>

Both approaches can exercise a real service. Stove builds on **Testcontainers and WireMock**, giving you a shared lifecycle, a Kotlin DSL, and reporting across the systems in your test.

</div>

<nav class="comparison-jumps" aria-label="On this page">
  <a href="#scenario">The flow</a>
  <a href="#prepare">01 · Prepare</a>
  <a href="#assert">02 · Assert</a>
  <a href="#investigate">03 · Investigate</a>
  <a href="#responsibilities">What changes</a>
  <a href="#fit">Is it a fit?</a>
</nav>

## One flow. The same expectations. { #scenario }

Place an order: the app checks inventory over HTTP, persists the order, then sends an `OrderCreated` event. The inventory service is mocked at its network boundary; the application, PostgreSQL, and Kafka are real.

<ol class="comparison-flow" aria-label="Order flow, in execution order">
  <li><span class="comparison-tag">Test client</span><strong>POST /orders</strong><span>Send a unique order</span></li>
  <li><span class="comparison-tag">Real application</span><strong>Spring Boot</strong><span>Run business logic</span></li>
  <li class="comparison-flow-mock"><span class="comparison-tag">Mocked boundary</span><strong>Inventory HTTP</strong><span>Return to the app</span></li>
  <li><span class="comparison-tag">Real database</span><strong>PostgreSQL</strong><span>App persists the order</span></li>
  <li><span class="comparison-tag">Real broker</span><strong>Kafka</strong><span>App sends the event</span></li>
</ol>

The app makes each downstream call; the arrows show execution order. Success means **HTTP 201 with status `CREATED`**, **one matching database row**, and **an event with the matching user and item on `order.created.v1`**, within a 10-second Kafka assertion window.

!!! note "How to read the examples"
    These are illustrative adaptations of the [order-flow recipe](recipes/order-flow.md) and the [maintained Spring showcase](https://github.com/Trendyol/stove/tree/main/recipes/jvm/kotlin-recipes/spring-showcase). Imports, DTO definitions, and application code are omitted. The smaller `/orders` scenario uses the recipe's names; it is not a copy of the showcase's `/api/orders` application. Both versions assume JSON events, application-managed schema/topic creation, and fresh UUID-based user and item IDs per test. Run this example serially; shared infrastructure still needs an isolation policy.

## 01 · Prepare the application and dependencies { #prepare }

Decide which systems to run and how their connection details reach the application. The infrastructure can be shared across tests in either approach.

<div class="comparison-pair" markdown="1">
<div class="comparison-panel" markdown="1">

### Spring Boot + Testcontainers + supporting libraries

Use Spring's test context and service connections for PostgreSQL and Kafka. Register WireMock and its application-specific URL in the same context.

```kotlin
@SpringBootTest(webEnvironment = RANDOM_PORT)
@Import(TestInfra::class)
class OrderFlowTest {
    @LocalServerPort var port: Int = 0
    @Autowired lateinit var jdbc: JdbcTemplate
    @Autowired lateinit var wiremock: WireMockServer
    @Autowired lateinit var kafka: KafkaContainer
    @Autowired lateinit var json: ObjectMapper

    // JUnit test uses these clients below.
}
```

<details markdown="1">
<summary>See the infrastructure and property wiring</summary>

```kotlin
@TestConfiguration(proxyBeanMethods = false)
class TestInfra {
    @Bean
    @ServiceConnection
    fun postgres() = PostgreSQLContainer("postgres:16-alpine")

    @Bean
    @ServiceConnection
    fun kafka() = KafkaContainer("apache/kafka-native:3.8.0")

    @Bean(initMethod = "start", destroyMethod = "stop")
    fun wiremock() = WireMockServer(
        WireMockConfiguration.options().dynamicPort()
    )

    @Bean
    fun inventoryUrl(server: WireMockServer) =
        DynamicPropertyRegistrar { properties ->
            properties.add("clients.inventory.url") {
                server.baseUrl()
            }
        }
}
```

This uses Spring Boot service connections, Spring Framework 6.2+ `DynamicPropertyRegistrar`, and Testcontainers 2.x (`org.testcontainers.postgresql.PostgreSQLContainer` and `org.testcontainers.kafka.KafkaContainer`). Spring manages the container beans and WireMock lifecycle. An older Spring stack can supply custom properties through `@DynamicPropertySource` instead.

The test stack includes Spring Boot Test, Spring Boot Testcontainers, the PostgreSQL/Kafka Testcontainers modules, WireMock, REST Assured, and Kafka clients. Spring supplies `JdbcTemplate` and the application's JSON mapper. Use your project's dependency management to align versions.

</details>

</div>
<div class="comparison-panel comparison-panel-stove" markdown="1">

### Stove

Register systems, map their configuration, and register the application runner last. The test extension adds failure reporting; startup and teardown remain explicit.

```kotlin
class StoveConfig : AbstractProjectConfig() {
    override val extensions = listOf(StoveKotestExtension())

    override suspend fun beforeProject() {
        startStove() // Expanded below.
    }

    override suspend fun afterProject() = Stove.stop()
}
```

<details markdown="1">
<summary>See the infrastructure and property wiring</summary>

```kotlin
suspend fun startStove() {
    val appPort = PortFinder.findAvailablePort()
    Stove().with {
        httpClient {
            HttpClientSystemOptions("http://localhost:$appPort")
        }
        wiremock {
            WireMockSystemOptions(
                port = 0,
                configureExposedConfiguration = { cfg ->
                    listOf("clients.inventory.url=${cfg.baseUrl}")
                }
            )
        }
        postgresql {
            PostgresqlOptions(
                databaseName = "orders",
                configureExposedConfiguration = { cfg ->
                    listOf(
                        "spring.datasource.url=${cfg.jdbcUrl}",
                        "spring.datasource.username=${cfg.username}",
                        "spring.datasource.password=${cfg.password}"
                    )
                }
            )
        }
        kafka {
            KafkaSystemOptions(
                serde = StoveSerde.jackson.anyByteArraySerde(),
                configureExposedConfiguration = { cfg ->
                    listOf(
                        "spring.kafka.bootstrap-servers=${cfg.bootstrapServers}",
                        "spring.kafka.producer.properties.interceptor.classes=${cfg.interceptorClass}"
                    )
                }
            )
        }
        springBoot(
            runner = { params -> com.yourcompany.orders.run(params) },
            withParameters = listOf("server.port=$appPort")
        )
    }.run()
}
```

The application's `run` entry point must forward the supplied arguments to Spring. Property names and serializers must match the application. The producer interceptor supplies the Kafka observations used below; use `stove-kafka` for this configuration.

Use the Stove BOM with `stove`, `stove-spring`, `stove-extensions-kotest`, `stove-http`, `stove-postgres`, `stove-kafka`, and `stove-wiremock`, plus the Kotest runner. Enable Kotest project-config discovery as described in [Getting Started](getting-started.md). The [wizard](wizard.md?rt=jvm&fw=spring-boot&test=kotest&sys=sys.http,sys.postgresql,sys.kafka&mk=sys.wiremock&obs=obs.reporting&br=0&step=6) generates the dependency and configuration starting point.

</details>

</div>
</div>

<p class="comparison-takeaway"><strong>What changes:</strong> one system-registration model owns the lifecycle. Your application still needs explicit property mappings, fixtures, and a schema. Stove does not discover those for you.</p>

## 02 · Exercise the flow and assert its effects { #assert }

Both excerpts use `userId = UUID.randomUUID().toString()` and `itemId = UUID.randomUUID().toString()`, with `CreateOrderRequest(userId, itemId, quantity = 1)`. The fresh IDs correlate database and Kafka assertions to this request and give the inventory stub a unique URL.

<div class="comparison-pair" markdown="1">
<div class="comparison-panel" markdown="1">

### Spring Boot + Testcontainers + supporting libraries

Compose the WireMock, REST Assured, JDBC, and Kafka APIs inside a JUnit test. Use the same assertion library if your team prefers it.

```kotlin
wiremock.stubFor(
    get(urlEqualTo("/inventory/$itemId"))
        .willReturn(okJson("""{"inStock":true}"""))
)

val response = given()
    .port(port)
    .contentType("application/json")
    .body(CreateOrderRequest(userId, itemId, 1))
    .post("/orders")
    .then().statusCode(201)
    .extract().`as`(OrderResponse::class.java)
response.status shouldBe "CREATED"

val statuses = jdbc.queryForList(
    "SELECT status FROM orders WHERE user_id = ?",
    String::class.java, userId
)
statuses shouldBe listOf("CREATED")

awaitOrderCreated(userId, itemId) // Expanded below.
```

<details markdown="1">
<summary>See the bounded Kafka assertion</summary>

```kotlin
fun awaitOrderCreated(userId: String, itemId: String) {
    val props = mapOf<String, Any>(
        "bootstrap.servers" to kafka.bootstrapServers,
        "group.id" to "comparison-${UUID.randomUUID()}",
        "auto.offset.reset" to "earliest",
        "enable.auto.commit" to false
    )
    KafkaConsumer(props, StringDeserializer(), StringDeserializer()).use { consumer ->
        consumer.subscribe(listOf("order.created.v1"))
        val deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos()
        while (true) {
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0) break
            val records = consumer.poll(
                Duration.ofNanos(minOf(remaining, 250_000_000L))
            )
            if (records.any { record ->
                val event = json.readValue(
                    record.value(), OrderCreated::class.java
                )
                event.userId == userId && event.itemId == itemId
            }) return
        }
        error("No OrderCreated for $userId / $itemId on order.created.v1 within 10s")
    }
}
```

`Duration` here is `java.time.Duration`. This consumer has its own group, starts at the earliest available record, filters by the fresh IDs, and closes even on failure. It reads broker records; it does not prove that an application consumer processed the event.

</details>

</div>
<div class="comparison-panel comparison-panel-stove" markdown="1">

### Stove

Express each interaction inside a system block in one `stove { }` scope, within a Kotest test. Use Kotest matchers for the response and rows.

```kotlin
stove {
    wiremock {
        mockGet("/inventory/$itemId") {
            respond { json(InventoryResponse(inStock = true)) }
        }
    }
    http {
        postAndExpectBody<OrderResponse>(
            uri = "/orders",
            body = CreateOrderRequest(userId, itemId, 1).some()
        ) { response ->
            response.status shouldBe 201
            response.body().status shouldBe "CREATED"
        }
    }
    postgresql {
        shouldQuery<String>(
            query = "SELECT status FROM orders WHERE user_id = '$userId'",
            mapper = { row -> row.string("status") }
        ) { statuses ->
            statuses shouldBe listOf("CREATED")
        }
    }
    kafka {
        shouldBePublished<OrderCreated>(atLeastIn = 10.seconds) {
            metadata.topic == "order.created.v1" &&
                actual.userId == userId && actual.itemId == itemId
        }
    }
}
```

The SQL above interpolates only the test-generated UUID. Use query parameters for external input. `some()` comes from Arrow; `seconds` is Kotlin's duration extension.

</div>
</div>

!!! info "Same event predicate; different observation points"
    The DIY consumer reads from the broker. Stove's `shouldBePublished` waits for a matching producer observation from the configured interceptor; this alone does not establish broker acknowledgement or downstream processing. Both examples match the output topic and fresh IDs with a 10-second wait. When broker delivery is your requirement, use Stove's [Kafka consumer DSL](Components/02-kafka.md#inflight-consumer-standalone) to read broker records; when application consumption is the requirement, use `shouldBeConsumed` with the appropriate observation wiring.

<p class="comparison-takeaway"><strong>What changes:</strong> system blocks provide a consistent place to interact, assert, and record evidence. You still choose the right observation point, correlation fields, and timeout for the behavior you care about.</p>

## 03 · Investigate the same missing event { #investigate }

The request succeeds and the row exists, but the expected event never appears. Both tests fail. What evidence is beside the assertion?

<div class="comparison-pair" markdown="1">
<div class="comparison-panel" markdown="1">

### Spring Boot + Testcontainers + supporting libraries

The helper reports the topic, correlation values, and timeout. Spring, broker, and WireMock logs remain available; your suite can collect and attach them, or add tracing and custom assertion diagnostics.

```text
Illustrative failure

No OrderCreated for user-42 / item-7
on order.created.v1 within 10s

Next: inspect producer logs, broker records,
and inventory requests for this order.
```

How much context arrives with the failure depends on the integrations your team has configured.

</div>
<div class="comparison-panel comparison-panel-stove" markdown="1">

### Stove

With the test extension registered, the failure report includes recorded system activity and snapshots. HTTP assertions, test-side database queries, mock interactions, and Kafka observations can be inspected together.

```text
Illustrative evidence, condensed

HTTP        POST /orders → 201, CREATED
PostgreSQL  test query → [CREATED]
WireMock    inventory request matched
Kafka       OrderCreated predicate timed out
            expected: order.created.v1
            observed: order.failed.v1
```

An observed event on another topic is a lead to investigate. A timeout can also mean missing observation wiring; the report is evidence, not an automatic root-cause verdict. The rows above illustrate available evidence, not literal output formatting or an exact event sequence.

</div>
</div>

<div class="comparison-evidence" markdown="1">

**Go deeper when you need it.** [Reporting](Components/13-reporting.md) works without the dashboard or tracing. Enable [tracing](Components/15-tracing.md) and instrument the application for internal spans; run the server and register [dashboard](Components/18-dashboard.md) ingestion to browse stored runs. [MCP](Components/21-mcp.md) exposes stored evidence to agents. These are additional setup choices, not prerequisites for the comparison's test.

[Walk through a failed test →](observability/when-it-fails.md)

</div>

## What changes in your test harness? { #responsibilities }

| Responsibility | Assembled stack | Stove |
|---|---|---|
| Dependency lifecycle | Testcontainers and test lifecycle integration | Registered Stove systems |
| Application configuration | Framework connection support plus custom properties | Explicit mappings passed through the runner |
| Cross-system assertions | HTTP, database, and Kafka APIs composed in tests | System-specific blocks within one Kotlin DSL |
| Failure context | Assertions, logs, and configured diagnostics | Registered-system timeline and snapshots through the test extension |
| Deeper investigation | Separately integrated tooling | Optional tracing, dashboard, and MCP |

Stove uses Testcontainers for container-backed dependencies and WireMock for HTTP mocks. Adopting Stove changes how you compose those tools; it does not remove the need to understand your application or infrastructure.

## Is Stove a fit for your service? { #fit }

<div class="comparison-pair" markdown="1">
<div class="comparison-panel comparison-panel-stove" markdown="1">

### Useful when the harness keeps growing

- Tests repeatedly coordinate a database, broker, and external services.
- Teams maintain similar startup, configuration, and assertion helpers across services.
- Failures require piecing together evidence from several systems.
- Your team wants Kotlin-authored tests, including for applications using other supported runners.

</div>
<div class="comparison-panel" markdown="1">

### Your current setup may already be enough

- A small HTTP-only suite has little infrastructure to coordinate.
- A maintained internal harness already provides the lifecycle and diagnostics you need.
- Unit or focused integration tests cover the behavior under investigation.
- Your team prefers another test-authoring language.

</div>
</div>

**Plan for the real costs.** The default container-backed setup needs a Docker-compatible runtime locally and in CI. You still manage dependencies, property mappings, schemas, fixtures, and test isolation. System cleanup callbacks run at `Stove.stop()`, not automatically between tests. Tracing needs instrumentation, and the dashboard needs a running server. [Provided instances](Components/11-provided-instances.md) are an option when dependencies already exist.

<div class="comparison-next" markdown="1">

### Try it on one flow

Start with the HTTP, PostgreSQL, Kafka, and WireMock setup from this page. Add deeper observability when it helps your team diagnose failures.

<div class="comparison-actions" markdown="1">

[Open this setup in the wizard](wizard.md?rt=jvm&fw=spring-boot&test=kotest&sys=sys.http,sys.postgresql,sys.kafka&mk=sys.wiremock&obs=obs.reporting&br=0&step=6){ .stove-btn .primary }
[Read the order-flow recipe](recipes/order-flow.md){ .stove-btn }
[Explore the dashboard demo](https://trendyol.github.io/stove/dashboard-demo/){ .stove-btn }

</div>

The dashboard demo runs in your browser with **fictional test data**. It demonstrates the investigation experience, not a live execution of these excerpts.

</div>
</div>
