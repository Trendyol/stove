
[![OpenSSF Scorecard](https://api.scorecard.dev/projects/github.com/Trendyol/stove/badge)](https://scorecard.dev/viewer/?uri=github.com/Trendyol/stove)
<p align="center">
  <img src="docs/assets/stove-mark.svg" alt="Stove logo" width="96" height="96"/>
</p>

<h1 align="center">Stove</h1>

<p align="center">
  Kotlin-first end-to-end testing for JVM and polyglot applications.<br/>
  Boot the application under test, wire real dependencies, and assert the full runtime flow from one DSL.
</p>

<p align="center">
  <img src="https://img.shields.io/maven-central/v/com.trendyol/stove?versionPrefix=0&label=release&color=blue" alt="Release"/>
  <a href="https://github.com/Trendyol/homebrew-trendyol-tap"><img src="https://img.shields.io/github/v/release/Trendyol/stove?label=StoveCLI(homebrew)&logo=homebrew&color=FBB040" alt="Homebrew"/></a>
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

The JVM ecosystem has mature frameworks for building applications, but end-to-end test setup is still fragmented.
Testcontainers can start infrastructure, but most teams still write their own lifecycle code for container startup,
runtime configuration, application boot, cleanup, and assertions. That boilerplate usually looks different for every
framework.

Stove puts those pieces behind one lifecycle. You register the systems your app talks to, then register one AUT runner.
Stove starts or connects to the systems, exposes their runtime configuration to framework/process/container AUT runners,
boots or targets the application under test (AUT), and gives your tests a single Kotlin DSL for driving and verifying the flow.
A system is a Stove dependency, client, mock, or observability module such as HTTP, PostgreSQL, Kafka, WireMock, tracing,
or dashboard. An AUT runner registers how Stove starts or targets the app.

Stove works with Java, Kotlin, and Scala applications across Spring Boot, Ktor, Micronaut, and Quarkus. The same test DSL
also supports non-JVM applications through process/container runners, or targets already-running applications with
`providedApplication()`. Because assertions are system-oriented rather than framework-specific, teams can verify HTTP
APIs, async message flows, database side effects, external service calls, and traces without rewriting the test model for
each stack.

**What Stove does:**

- Starts dependencies with Testcontainers or connects to **provided** infrastructure (existing PostgreSQL, MySQL, Kafka, etc.)
- Passes generated connection details to framework, process, or container runners before the AUT starts
- Starts your **actual** application through a framework, process, or container runner, or targets an already-running app with `providedApplication()`
- Exposes one DSL for HTTP, database, Kafka, WireMock, gRPC, tracing, and custom-system assertions; dashboard adds reporting evidence when enabled
- Provides DI-container access for supported JVM frameworks via `bridge()` and `using<T> { ... }`
- For in-process JVM runners, keeps breakpoints and e2e coverage in the same runtime path your use case follows
- Supports Spring Boot, Ktor, Micronaut, Quarkus, and non-JVM apps through process/container modes
- Extensible architecture for adding new components and
  frameworks ([Writing Custom Systems](https://trendyol.github.io/stove/writing-custom-systems/))

## Dashboard (New in 0.23.0)

Stove Dashboard is a local UI and API for end-to-end test runs. When the `stove` CLI is running and `dashboard { }` is
registered, it receives events from your test JVM, stores run data in SQLite, and shows timelines, system snapshots, and
traces in one place. Trace data still requires the tracing setup shown below.

https://github.com/user-attachments/assets/14597dc6-e9d4-43ab-8cfa-578ab3c3e6df

**Quick start**

```bash
# 1) Install and start the Dashboard CLI
brew install Trendyol/trendyol-tap/stove
stove

# 2) Run your tests and open the dashboard
./gradlew test
# http://localhost:4040
```

```kotlin
// build.gradle.kts
plugins {
  id("com.trendyol.stove.tracing") version "$stoveVersion"
}

dependencies {
  testImplementation(platform("com.trendyol:stove-bom:$version"))
  testImplementation("com.trendyol:stove-extensions-kotest")  // or stove-extensions-junit
  testImplementation("com.trendyol:stove-dashboard")
  testImplementation("com.trendyol:stove-tracing")
}

stoveTracing {
  serviceName.set("product-api")
}
```

```kotlin
// Kotest
class StoveConfig : AbstractProjectConfig() {
  override val extensions = listOf(StoveKotestExtension())
  override suspend fun beforeProject() {
    Stove().with {
      dashboard { DashboardSystemOptions(appName = "product-api") }
      tracing { enableSpanReceiver() } // recommended
    }.run()
  }
  override suspend fun afterProject() = Stove.stop()
}

// JUnit
@ExtendWith(StoveJUnitExtension::class)
abstract class BaseE2ETest { /* Stove().with { ... }.run() in @BeforeAll */ }
```

Keep `stove-cli`, the Stove BOM, the tracing Gradle plugin, and your Stove test dependencies on the same Stove version. The dashboard warns on version mismatches, but aligning versions avoids missing or inconsistent dashboard data.

See [Dashboard docs](https://trendyol.github.io/stove/Components/18-dashboard/) and
[0.23.0 release notes](https://trendyol.github.io/stove/release-notes/0.23.0/) for full details.

## Getting Started

**1. Add dependencies**

```kotlin
dependencies {
  // Import BOM for version management
  testImplementation(platform("com.trendyol:stove-bom:$version"))
  
  // Core and framework starter
  testImplementation("com.trendyol:stove")
  testImplementation("com.trendyol:stove-spring")  // or stove-ktor, stove-micronaut, stove-quarkus
  
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

**2. Configure Stove** (runs once before the e2e suite)

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

All assertions happen inside `stove { }`. Each block resolves the system registered in `Stove().with { ... }`, so test
code stays focused on the behavior under test instead of client construction or container plumbing.

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

For supported JVM frameworks, `bridge()` exposes the application DI container so a test can inspect or call beans after
driving the public API:

```kotlin
using<OrderService> { processOrder(orderId) }
using<UserRepo, EmailService> { userRepo, emailService ->
  userRepo.findById(id) shouldNotBe null
}
```

### Reporting

When the Kotest or JUnit extension is registered, Stove enriches failures with an execution report. The report records
the timeline of Stove operations and the latest snapshots each system can provide:

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

When tracing is enabled, failed tests can show the **execution call chain** inside your application: controllers,
services, database calls, Kafka publish/consume spans, and the failure point, powered by OpenTelemetry:

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

For in-process JVM applications launched by Stove with the tracing Gradle plugin, no application-code changes are
required. The plugin attaches the OpenTelemetry Java agent to the test JVM and configures the agent endpoint for the
application under test.

### AI Agent Integration

Stove's execution reports and tracing data are structured and deterministic, making them ideal for **AI agent workflows**. When an AI agent runs e2e tests during implementation, it can parse the failure reports — including the full execution trace, system snapshots, and timeline — to understand exactly what went wrong inside the application. This enables agents to iterate on fixes with precise feedback rather than guessing from opaque test failures.

When `stove` is running, it also exposes a local read-only MCP endpoint at `http://localhost:4040/mcp`. Agents can call `stove_failures` first, then drill into a specific `run_id + test_id` for timeline, trace, and snapshot evidence. MCP is optional: if it is unavailable or incomplete, agents should fall back to normal test output, Stove failure reports, and logs.

**Agent Skills:** Stove ships with a ready-to-use [Claude Code skill](https://github.com/Trendyol/stove/tree/main/.claude/skills/stove) that teaches AI agents how to set up and write Stove e2e tests. Copy the `.claude/skills/stove/` directory into your project's `.claude/skills/` folder, and your AI coding agent will know how to configure systems, write tests, enable tracing, and build custom systems — following all Stove conventions automatically.

## Configuration

### Framework Setup

<table>
<tr><th>Spring Boot</th><th>Ktor</th></tr>
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
    run(params, shouldWait = false)
  }
)
```

</td>
</tr>
<tr><th>Micronaut</th><th>Quarkus</th></tr>
<tr>
<td>

```kotlin
micronaut(
  runner = { params ->
    myApp.run(params)
  }
)
```

</td>
<td>

```kotlin
quarkus(
  runner = { params ->
    MyApp.main(params)
  }
)
```

</td>
</tr>
</table>

### Container Reuse

Speed up local development by keeping reusable dependency containers running between test runs:

```kotlin
Stove { keepDependenciesRunning() }.with { ... }
```

### Cleanup

Run cleanup logic when Stove stops at suite teardown:

```kotlin
postgresql {
  PostgresqlOptions(cleanup = { it.execute("TRUNCATE users") }, ...)
}

kafka {
  KafkaSystemOptions(cleanup = { it.deleteTopics(listOf("test-topic")) }, ...)
}
```

Available for Kafka, PostgreSQL, MySQL, MongoDB, Couchbase, Cassandra, MSSQL, Elasticsearch, Redis.

### Migrations

Run system migrations during suite startup before the application under test receives dependency configuration:

```kotlin
postgresql {
  PostgresqlOptions(...)
   .migrations {
      register<CreateUsersTable>()
      register<CreateOrdersTable>()
  }
}
```

Available for Kafka, PostgreSQL, MySQL, MongoDB, Couchbase, Cassandra, MSSQL, Elasticsearch, Redis.

### Provided Instances

Connect to existing infrastructure instead of starting Testcontainers (useful when CI already provides shared services):

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
| Databases  | PostgreSQL, MySQL, MongoDB, Couchbase, Cassandra, MSSQL, Elasticsearch, Redis |
| Messaging  | Kafka                                                       |
| HTTP       | Built-in client, WebSockets, WireMock                       |
| gRPC       | Client (grpc-kotlin), Mock Server (native)                  |
| Frameworks | Spring Boot, Ktor, Micronaut, Quarkus                       |

### Feature Matrix

| Component     | Migrations | Cleanup | Provided Instance | Pause/Unpause |
|---------------|:----------:|:-------:|:-----------------:|:-------------:|
| PostgreSQL    |     ✅      |    ✅    |         ✅         |       ✅       |
| MySQL         |     ✅      |    ✅    |         ✅         |       ✅       |
| MSSQL         |     ✅      |    ✅    |         ✅         |       ✅       |
| MongoDB       |     ✅      |    ✅    |         ✅         |       ✅       |
| Couchbase     |     ✅      |    ✅    |         ✅         |       ✅       |
| Cassandra     |     ✅      |    ✅    |         ✅         |       ✅       |
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
- **[AI Agent Skill](https://github.com/Trendyol/stove/tree/main/.claude/skills/stove)**: Drop into `.claude/skills/` to teach AI agents Stove conventions
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
