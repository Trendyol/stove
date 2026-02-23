
[![OpenSSF Scorecard](https://api.scorecard.dev/projects/github.com/Trendyol/stove/badge)](https://scorecard.dev/viewer/?uri=github.com/Trendyol/stove)
<h1 align="center">Stove</h1>

<p align="center">
  End-to-end testing framework for the JVM.<br/>
  Test your application against real infrastructure with a unified Kotlin DSL.
</p>

<p align="center">
  <img src="https://img.shields.io/maven-central/v/com.trendyol/stove?versionPrefix=0&label=release&color=blue" alt="Release"/>
  <a href="https://central.sonatype.com/service/rest/repository/browse/maven-snapshots/com/trendyol/"><img src="https://img.shields.io/badge/dynamic/xml?url=https%3A%2F%2Fcentral.sonatype.com%2Frepository%2Fmaven-snapshots%2Fcom%2Ftrendyol%2Fstove%2Fmaven-metadata.xml&query=%2F%2Fmetadata%2Fversioning%2Flatest&label=snapshot&color=orange" alt="Snapshot"/></a>
  <a href="https://codecov.io/gh/Trendyol/stove"><img src="https://codecov.io/gh/Trendyol/stove/graph/badge.svg?token=HcKBT3chO7" alt="codecov"/></a>
  <a href="https://scorecard.dev/viewer/?uri=github.com/Trendyol/stove"><img src="https://img.shields.io/ossf-scorecard/github.com/Trendyol/stove?label=openssf%20scorecard&style=flat" alt="OpenSSF Scorecard"/></a>
</p>

```kotlin
stove {
  // Call API and verify response
  http {
    postAndExpectBodilessResponse("/orders", body = CreateOrderRequest(userId, productId).some()) {
      it.status shouldBe 201
    }
  }

  // Verify database state
  postgresql {
    shouldQuery<Order>("SELECT * FROM orders WHERE user_id = '$userId'", mapper = { row ->
      Order(row.string("status"))
    }) {
      it.first().status shouldBe "CONFIRMED"
    }
  }

  // Verify event was published
  kafka {
    shouldBePublished<OrderCreatedEvent> {
      actual.userId == userId
    }
  }

  // Access application beans directly
  using<InventoryService> {
    getStock(productId) shouldBe 9
  }
}
```

## Why Stove?

The JVM ecosystem has excellent frameworks for building applications, but e2e testing remains fragmented. Testcontainers
handles infrastructure, but you still write boilerplate for configuration, app startup, and assertions. Differently for
each framework.

Stove explores how the testing experience on the JVM can be improved by unifying assertions and the supporting
infrastructure. It creates a concise and expressive testing DSL by leveraging Kotlin's unique language features.

Stove works with Java, Kotlin, and Scala applications across Spring Boot, Ktor, and Micronaut. Because tests are
framework-agnostic, teams can migrate between stacks without rewriting test code. It empowers developers to write clear
assertions even for code that is traditionally hard to test (async flows, message consumers, database side effects).

**What Stove does:**

- Starts containers via Testcontainers or connect **provided** infra (PostgreSQL, MySQL, Kafka, etc.)
- Launches your **actual** application with test configuration
- Exposes a unified DSL for assertions across all components
- Provides access to your DI container from tests
- Debug your entire use case with one click (breakpoints work everywhere)
- Get code coverage from e2e test execution
- Supports Spring Boot, Ktor, Micronaut
- Extensible architecture for adding new components and
  frameworks ([Writing Custom Systems](https://trendyol.github.io/stove/writing-custom-systems/))

<p align="center"><img src="./docs/assets/stove_architecture.svg" with="600" /></p>

## Getting Started

**1. Add dependencies**

```kotlin
dependencies {
  // Import BOM for version management
  testImplementation(platform("com.trendyol:stove-bom:$version"))
  
  // Core and framework starter
  testImplementation("com.trendyol:stove")
  testImplementation("com.trendyol:stove-spring")  // or stove-ktor, stove-micronaut
  
  // Component modules
  testImplementation("com.trendyol:stove-postgres")
  testImplementation("com.trendyol:stove-mysql")
  testImplementation("com.trendyol:stove-kafka")
}
```

> **Snapshots:** As of 5th June 2025, Stove's snapshot packages are hosted on [Central Sonatype](https://central.sonatype.com/service/rest/repository/browse/maven-snapshots/com/trendyol/).
> ```kotlin
> repositories {
>   maven("https://central.sonatype.com/repository/maven-snapshots")
> }
> ```

**2. Configure Stove** (runs once before all tests)

```kotlin
class StoveConfig : AbstractProjectConfig() {
  override suspend fun beforeProject() = Stove()
    .with {
      httpClient {
        HttpClientSystemOptions(baseUrl = "http://localhost:8080")
      }
      postgresql {
        PostgresqlOptions(
          cleanup = { it.execute("TRUNCATE orders, users") },
          configureExposedConfiguration = { listOf("spring.datasource.url=${it.jdbcUrl}") }
        ).migrations {
          register<CreateUsersTable>()
        }
      }
      kafka {
        KafkaSystemOptions(
          cleanup = { it.deleteTopics(listOf("orders")) },
          configureExposedConfiguration = { listOf("kafka.bootstrapServers=${it.bootstrapServers}") }
        ).migrations {
          register<CreateOrdersTopic>()
        }
      }
      bridge()
      springBoot(runner = { params ->
        myApp.run(params) { addTestDependencies() }
      })
    }.run()

  override suspend fun afterProject() = Stove.stop()
}
```

**3. Write tests**

```kotlin
test("should process order") {
  stove {
    http {
      get<Order>("/orders/123") {
        it.status shouldBe "CONFIRMED"
      }
    }
    postgresql {
      shouldQuery<Order>("SELECT * FROM orders", mapper = { row ->
        Order(row.string("status"))
      }) {
        it.size shouldBe 1
      }
    }
    kafka {
      shouldBePublished<OrderCreatedEvent> {
        actual.orderId == "123"
      }
    }
  }
}
```

## Writing Tests

All assertions happen inside `stove { }`. Each component has its own DSL block.

### HTTP

```kotlin
http {
  get<User>("/users/$id") {
    it.name shouldBe "John"
  }
  postAndExpectBodilessResponse("/users", body = request.some()) {
    it.status shouldBe 201
  }
  postAndExpectBody<User>("/users", body = request.some()) {
    it.id shouldNotBe null
  }
}
```

### Database

```kotlin
postgresql {  // also: mysql, mongodb, couchbase, mssql, elasticsearch, redis
  shouldExecute("INSERT INTO users (name) VALUES ('Jane')")
  shouldQuery<User>("SELECT * FROM users", mapper = { row ->
    User(row.string("name"))
  }) {
    it.size shouldBe 1
  }
}
```

### Kafka

```kotlin
kafka {
  publish("orders.created", OrderCreatedEvent(orderId = "123"))
  shouldBeConsumed<OrderCreatedEvent> {
    actual.orderId == "123"
  }
  shouldBePublished<OrderConfirmedEvent> {
    actual.orderId == "123"
  }
}
```

### External API Mocking

```kotlin
wiremock {
  mockGet("/external-api/users/1", responseBody = User(id = 1, name = "John").some())
  mockPost("/external-api/notify", statusCode = 202)
}
```

### Application Beans

Access your DI container directly via `bridge()`:

```kotlin
using<OrderService> { processOrder(orderId) }
using<UserRepo, EmailService> { userRepo, emailService ->
  userRepo.findById(id) shouldNotBe null
}
```

### Reporting

When tests fail, Stove automatically enriches exceptions with a detailed execution report showing exactly what happened:

<details>
<summary><strong>Example Report</strong></summary>

```
╔══════════════════════════════════════════════════════════════════════════════════════════════════╗
║                                   STOVE TEST EXECUTION REPORT                                    ║
║                                                                                                  ║
║ Test: should create new product when send product create request from api for the allowed        ║
║ supplier                                                                                         ║
║ ID: ExampleTest::should create new product when send product create request from api for the     ║
║ allowed supplier                                                                                 ║
║ Status: FAILED                                                                                   ║
╠══════════════════════════════════════════════════════════════════════════════════════════════════╣
║                                                                                                  ║
║ TIMELINE                                                                                         ║
║ ────────                                                                                         ║
║                                                                                                  ║
║ 12:41:12.371 ✓ PASSED [WireMock] Register stub: GET /suppliers/99/allowed                        ║
║     Output: kotlin.Unit                                                                          ║
║     Metadata: {statusCode=200, responseHeaders={}}                                               ║
║                                                                                                  ║
║ 12:41:13.405 ✓ PASSED [HTTP] POST /api/product/create                                            ║
║     Input: ProductCreateRequest(id=1, name=product name, supplierId=99)                          ║
║     Output: kotlin.Unit                                                                          ║
║     Metadata: {status=200, headers={}}                                                           ║
║                                                                                                  ║
║ 12:41:13.424 ✓ PASSED [Kafka] shouldBePublished<ProductCreatedEvent>                             ║
║     Output: ProductCreatedEvent(id=1, name=product name, supplierId=99, createdDate=Thu Jan 08   ║
║     12:41:12 CET 2026, type=ProductCreatedEvent)                                                 ║
║     Metadata: {timeout=5s}                                                                       ║
║                                                                                                  ║
║ 12:41:13.455 ✗ FAILED [Couchbase] Get document                                                   ║
║     Input: {id=product:1}                                                                        ║
║     Error: expected:<100L> but was:<99L>                                                         ║
║                                                                                                  ║
╠══════════════════════════════════════════════════════════════════════════════════════════════════╣
║                                                                                                  ║
║ SYSTEM SNAPSHOTS                                                                                 ║
║ ────────────────                                                                                 ║
║                                                                                                  ║
║ ┌─ HTTP ──────────────────────────────────────────────────────────────────────────────────────── ║
║                                                                                                  ║
║   No detailed state available                                                                    ║
║                                                                                                  ║
║ ┌─ COUCHBASE ─────────────────────────────────────────────────────────────────────────────────── ║
║                                                                                                  ║
║   No detailed state available                                                                    ║
║                                                                                                  ║
║ ┌─ KAFKA ─────────────────────────────────────────────────────────────────────────────────────── ║
║                                                                                                  ║
║   Consumed: 0                                                                                    ║
║   Published: 1                                                                                   ║
║   Committed: 0                                                                                   ║
║                                                                                                  ║
║   State Details:                                                                                 ║
║     consumed: 0 item(s)                                                                          ║
║     published: 1 item(s)                                                                         ║
║       [0]                                                                                        ║
║         id: 376db940-a367-4419-a628-4754c9466421                                                 ║
║         topic: stove-standalone-example.productCreated.1                                         ║
║         key: 1                                                                                   ║
║         headers: {X-EventType=ProductCreatedEvent, X-MessageId=29902970-056d-4ae9-9a84-...}      ║
║         message: {"id":1,"name":"product name","supplierId":99,...}                              ║
║     committed: 0 item(s)                                                                         ║
║                                                                                                  ║
║ ┌─ WIREMOCK ──────────────────────────────────────────────────────────────────────────────────── ║
║                                                                                                  ║
║   Registered stubs: 0                                                                            ║
║   Served requests: 0 (matched: 0)                                                                ║
║   Unmatched requests: 0                                                                          ║
║                                                                                                  ║
╚══════════════════════════════════════════════════════════════════════════════════════════════════╝
```

</details>

**Features:**
- Timeline of all operations with timestamps and results
- Input/output for each action
- Expected vs actual values on failures
- System snapshots (Kafka messages, WireMock stubs, etc.)

**Test Framework Extensions:**

Use the provided extensions to automatically enrich failures:

```kotlin
// Kotest - register in project config
class StoveConfig : AbstractProjectConfig() {
  override val extensions = listOf(StoveKotestExtension())
}

// JUnit 5 - annotate test class
@ExtendWith(StoveJUnitExtension::class)
class MyTest { ... }
```

**Configuration:**

```kotlin
Stove(
  StoveOptions(
    reportingEnabled = true,           // Enable/disable reporting (default: true)
    dumpReportOnTestFailure = true,    // Enrich failures with report (default: true)
    failureRenderer = PrettyConsoleRenderer  // Custom renderer (default: PrettyConsoleRenderer)
  )
).with { ... }
```

### Tracing

When a test fails, see the **entire execution call chain** inside your application — every controller, service, database query, and Kafka message — powered by OpenTelemetry:

```
EXECUTION TRACE (Call Chain)
═══════════════════════════════════════════════════════════════════
✓ POST (377ms)
  ✓ POST /api/product/create (361ms)
    ✓ ProductController.create (141ms)
      ✓ ProductCreator.create (0ms)
      ✓ KafkaProducer.send (137ms)
        ✓ orders.created publish (81ms)
          ✗ orders.created process (82ms)  ← FAILURE POINT
```

**Setup** (two steps):

```kotlin
// 1. In your Stove config
tracing { enableSpanReceiver() }

// 2. In build.gradle.kts
plugins { id("com.trendyol.stove.tracing") version "$stoveVersion" }
stoveTracing { serviceName.set("my-service") }
```

**Validate traces in tests:**

```kotlin
tracing {
    shouldContainSpan("OrderService.processOrder")
    shouldNotHaveFailedSpans()
    executionTimeShouldBeLessThan(500.milliseconds)
}
```

No code changes to your application required. The OpenTelemetry agent instruments 100+ libraries automatically.

### AI Agent Integration

Stove's execution reports and tracing data are structured and deterministic, making them ideal for **AI agent workflows**. When an AI agent runs e2e tests during implementation, it can parse the failure reports — including the full execution trace, system snapshots, and timeline — to understand exactly what went wrong inside the application. This enables agents to iterate on fixes with precise feedback rather than guessing from opaque test failures.

**Agent Skills:** Stove ships with a ready-to-use [Claude Code skill](https://github.com/Trendyol/stove/tree/main/.claude/skills/stove-e2e-setup) that teaches AI agents how to set up and write Stove e2e tests. Copy the `.claude/skills/stove-e2e-setup/` directory into your project's `.claude/skills/` folder, and your AI coding agent will know how to configure systems, write tests, enable tracing, and build custom systems — following all Stove conventions automatically.

## Configuration

### Framework Setup

<table>
<tr><th>Spring Boot</th><th>Ktor</th><th>Micronaut</th></tr>
<tr>
<td>

```kotlin
springBoot(
  runner = { params ->
    myApp.run(params) {
      addTestDependencies()
    }
  }
)
```

</td>
<td>

```kotlin
ktor(
  runner = { params ->
    myApp.run(params) {
      addTestDependencies()
    }
  }
)
```

</td>
<td>

```kotlin
micronaut(
  runner = { params ->
    myApp.run(params) {
      addTestDependencies()
    }
  }
)
```

</td>
</tr>
</table>

### Container Reuse

Speed up local development by keeping containers running between test runs:

```kotlin
Stove { keepDependenciesRunning() }.with { ... }
```

### Cleanup

Run cleanup logic after tests complete:

```kotlin
postgresql {
  PostgresqlOptions(cleanup = { it.execute("TRUNCATE users") }, ...)
}

kafka {
  KafkaSystemOptions(cleanup = { it.deleteTopics(listOf("test-topic")) }, ...)
}
```

Available for Kafka, PostgreSQL, MySQL, MongoDB, Couchbase, MSSQL, Elasticsearch, Redis.

### Migrations

Run database migrations before tests start:

```kotlin
postgresql {
  PostgresqlOptions(...)
  .migrations {
  register<CreateUsersTable>()
  register<CreateOrdersTable>()
}
}
```

Available for Kafka, PostgreSQL, MySQL, MongoDB, Couchbase, MSSQL, Elasticsearch, Redis.

### Provided Instances

Connect to existing infrastructure instead of starting containers (useful for CI/CD):

```kotlin
postgresql { PostgresqlOptions.provided(jdbcUrl = "jdbc:postgresql://ci-db:5432/test", ...) }
kafka { KafkaSystemOptions.provided(bootstrapServers = "ci-kafka:9092", ...) }
```

> **Tip:** When using provided instances, use migrations to create isolated test schemas and cleanups to remove test
> data afterwards. This ensures test isolation on shared infrastructure.

<strong>Complete Example</strong>

```kotlin
test("should create order with payment processing") {
  stove {
    val userId = UUID.randomUUID().toString()
    val productId = UUID.randomUUID().toString()

    // 1. Seed database
    postgresql {
      shouldExecute("INSERT INTO users (id, name) VALUES ('$userId', 'John')")
      shouldExecute("INSERT INTO products (id, price, stock) VALUES ('$productId', 99.99, 10)")
    }

    // 2. Mock external payment API
    wiremock {
      mockPost(
        "/payments/charge", statusCode = 200,
        responseBody = PaymentResult(success = true).some()
      )
    }

    // 3. Call API
    http {
      postAndExpectBody<OrderResponse>(
        "/orders",
        body = CreateOrderRequest(userId, productId).some()
      ) {
        it.status shouldBe 201
      }
    }

    // 4. Verify database
    postgresql {
      shouldQuery<Order>("SELECT * FROM orders WHERE user_id = '$userId'", mapper = { row ->
        Order(row.string("status"))
      }) {
        it.first().status shouldBe "CONFIRMED"
      }
    }

    // 5. Verify event published
    kafka {
      shouldBePublished<OrderCreatedEvent> {
        actual.userId == userId
      }
    }

    // 6. Verify via application service
    using<InventoryService> { getStock(productId) shouldBe 9 }
  }
}
```

## Reference

### Supported Components

| Category   | Components                                                  |
|------------|-------------------------------------------------------------|
| Databases  | PostgreSQL, MySQL, MongoDB, Couchbase, MSSQL, Elasticsearch, Redis |
| Messaging  | Kafka                                                       |
| HTTP       | Built-in client, WebSockets, WireMock                       |
| gRPC       | Client (grpc-kotlin), Mock Server (native)                  |
| Frameworks | Spring Boot, Ktor, Micronaut, Quarkus (experimental)        |

### Feature Matrix

| Component     | Migrations | Cleanup | Provided Instance | Pause/Unpause |
|---------------|:----------:|:-------:|:-----------------:|:-------------:|
| PostgreSQL    |     ✅      |    ✅    |         ✅         |       ✅       |
| MySQL         |     ✅      |    ✅    |         ✅         |       ✅       |
| MSSQL         |     ✅      |    ✅    |         ✅         |       ✅       |
| MongoDB       |     ✅      |    ✅    |         ✅         |       ✅       |
| Couchbase     |     ✅      |    ✅    |         ✅         |       ✅       |
| Elasticsearch |     ✅      |    ✅    |         ✅         |       ✅       |
| Redis         |     ✅      |    ✅    |         ✅         |       ✅       |
| Kafka         |     ✅      |    ✅    |         ✅         |       ✅       |
| WireMock      |    n/a     |   n/a   |        n/a        |      n/a      |
| HTTP Client   |    n/a     |   n/a   |        n/a        |      n/a      |
| gRPC Mock     |    n/a     |   n/a   |        n/a        |      n/a      |

<details>
<summary><strong>FAQ</strong></summary>

**Can I use Stove with Java applications?**  
Yes. Your application can be Java, Scala, or any JVM language. Tests are written in Kotlin for the DSL.

**Does Stove replace Testcontainers?**  
No. Stove uses Testcontainers underneath and adds the unified DSL on top.

**How slow is the first run?**  
First run pulls Docker images (~1-2 min). Use `keepDependenciesRunning()` for instant subsequent runs.

**Can I run tests in parallel?**  
Yes, with unique test data per test.
See [provided instances docs](https://trendyol.github.io/stove/Components/11-provided-instances/).

</details>

## Resources

- **[Documentation](https://trendyol.github.io/stove/)**: Full guides and API reference
- **[Examples](https://github.com/Trendyol/stove/tree/main/examples)**: Working sample projects
- **[AI Agent Skill](https://github.com/Trendyol/stove/tree/main/.claude/skills/stove-e2e-setup)**: Drop into `.claude/skills/` to teach AI agents Stove conventions
- **[Blog Post](https://medium.com/trendyol-tech/a-new-approach-to-the-api-end-to-end-testing-in-kotlin-f743fd1901f5)**:
  Motivation and design decisions
- **[Video Walkthrough](https://youtu.be/DJ0CI5cBanc?t=669)**: Live demo (Turkish)

## Community

**Used by:**

1. [Trendyol](https://www.trendyol.com): Leading e-commerce platform, Turkey

*Using Stove? Open a PR to add your company.*

**Contributions:** [Issues](https://github.com/Trendyol/stove/issues) and PRs welcome  
**License:** Apache 2.0

> **Note:** Production-ready and used at scale. API still evolving; breaking changes possible in minor releases with
> migration guides.
