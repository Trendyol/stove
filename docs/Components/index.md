# Components

Stove uses a pluggable architecture. Each physical dependency is a separate module you add only when the test actually needs it. By default, components use <span data-rn="underline" data-rn-color="#ff9800">Testcontainers</span> under the hood, but they can also connect to [provided instances](11-provided-instances.md) (existing infrastructure) when Docker is unavailable or undesirable.

If you have not picked an application starter yet, start with [Supported Frameworks](../frameworks/index.md) first and then come back here for the physical dependencies.

Most teams start with 2 to 4 components, not the whole catalog.

## Start From The Workflow

<div class="grid cards" markdown>

-   **REST service with a database**

    Start with [HTTP Client](05-http.md), one database such as [PostgreSQL](06-postgresql.md), and optionally [WireMock](04-wiremock.md) for external calls.

-   **Kafka-driven workflow**

    Start with [Kafka](02-kafka.md), your main database, and [Tracing](15-tracing.md) for better failure diagnosis.

-   **Service calling external dependencies**

    Start with [HTTP Client](05-http.md), [WireMock](04-wiremock.md), or [gRPC Mock](14-grpc-mock.md) depending on the remote protocol.

-   **Hard-to-debug end-to-end failures**

    Add [Tracing](15-tracing.md) and [Reporting](13-reporting.md) early so failures show execution context instead of only raw assertions.

</div>

## Common Starting Sets

| You are testing | Start with |
|-----------------|------------|
| HTTP API backed by SQL | `stove-http` + `stove-postgres` or `stove-mysql` |
| Event-driven service | `stove-kafka` + your database + `stove-tracing` |
| External provider integration | `stove-http` + `stove-wiremock` |
| gRPC service | `stove-grpc` + `stove-grpc-mock` |
| Stateful service with caching | your database + `stove-redis` |

## Available Components

| Component | Module | Description |
|-----------|--------|-------------|
| [Kafka](02-kafka.md) | `stove-kafka` | Message broker for event-driven architectures |
| [Couchbase](01-couchbase.md) | `stove-couchbase` | NoSQL document database |
| [Elasticsearch](03-elasticsearch.md) | `stove-elasticsearch` | Search and analytics engine |
| [PostgreSQL](06-postgresql.md) | `stove-postgres` | Relational database |
| [MySQL](16-mysql.md) | `stove-mysql` | Relational database |
| [MongoDB](07-mongodb.md) | `stove-mongodb` | NoSQL document database |
| [MSSQL](08-mssql.md) | `stove-mssql` | Microsoft SQL Server |
| [Redis](09-redis.md) | `stove-redis` | In-memory data store |
| [WireMock](04-wiremock.md) | `stove-wiremock` | HTTP mock server for external services |
| [gRPC Mock](14-grpc-mock.md) | `stove-grpc-mock` | gRPC mock server for external gRPC services |
| [HTTP Client](05-http.md) | `stove-http` | HTTP client for testing your API |
| [gRPC](12-grpc.md) | `stove-grpc` | gRPC client for testing gRPC services |
| [Bridge](10-bridge.md) | Built-in | Access to application's DI container |
| [Tracing](15-tracing.md) | `stove-tracing` | Execution tracing with OpenTelemetry for failure diagnostics |
| [Provided Instances](11-provided-instances.md) | Built-in | Connect to existing infrastructure instead of containers |
| [Reporting](13-reporting.md) | `stove-extensions-kotest` or `stove-extensions-junit` | Rich failure reports with execution context |

## Quick Start

Add one starter, then only the components your scenario needs:

=== "Gradle"

    ```kotlin
    dependencies {
        testImplementation(platform("com.trendyol:stove-bom:$version"))
        testImplementation("com.trendyol:stove")

        // Pick one application starter
        testImplementation("com.trendyol:stove-spring")

        // Add only what the test touches
        testImplementation("com.trendyol:stove-http")
        testImplementation("com.trendyol:stove-postgres")
        testImplementation("com.trendyol:stove-wiremock")
        testImplementation("com.trendyol:stove-tracing")
    }
    ```

Swap in `stove-kafka`, `stove-redis`, `stove-grpc`, `stove-mongodb`, or other modules as your flow requires.

## Architecture Overview

Each component follows a consistent pattern:

1. **Configuration** - Define how the component should be set up
2. **Container/Runtime** - Manages the testcontainer or provided instance
3. **DSL** - Fluent API for test assertions
4. **Cleanup** - Automatic resource management

```kotlin
Stove()
  .with {
    // Each component is configured in the `with` block
    kafka { KafkaSystemOptions(...) }
    couchbase { CouchbaseSystemOptions(...) }
    http { HttpClientSystemOptions(...) }
    wiremock { WireMockSystemOptions(...) }
    tracing { enableSpanReceiver() }
    
    // Application under test
    springBoot(runner = { params -> myApp.run(params) })
  }
  .run() // Starts all components and the application

// Test your application
stove {
  http { /* HTTP assertions */ }
  kafka { /* Kafka assertions */ }
  couchbase { /* Database assertions */ }
}
```

## Component Categories

### Databases

| Type | Components | Use Case |
|------|------------|----------|
| Document | [Couchbase](01-couchbase.md), [MongoDB](07-mongodb.md), [Elasticsearch](03-elasticsearch.md) | JSON document storage, search |
| Relational | [PostgreSQL](06-postgresql.md), [MySQL](16-mysql.md), [MSSQL](08-mssql.md) | Structured data, transactions |
| Key-Value | [Redis](09-redis.md) | Caching, sessions, pub/sub |

### Messaging

| Component | Use Case |
|-----------|----------|
| [Kafka](02-kafka.md) | Event streaming, message queues, pub/sub |

### Network

| Component | Use Case |
|-----------|----------|
| [HTTP Client](05-http.md) | Testing your application's REST API |
| [gRPC](12-grpc.md) | Testing your application's gRPC services |
| [WireMock](04-wiremock.md) | Mocking external HTTP services |
| [gRPC Mock](14-grpc-mock.md) | Mocking external gRPC services |

### Application Integration

| Component | Use Case |
|-----------|----------|
| [Bridge](10-bridge.md) | Access application beans and services directly (supported by Spring, Ktor, and Micronaut starters) |
| [Reporting](13-reporting.md) | Detailed execution reports and failure diagnostics |
| [Tracing](15-tracing.md) | <span data-rn="highlight" data-rn-color="#00968855" data-rn-duration="800">Execution tracing with full call chain visibility on failure</span> |

## Common Configuration Pattern

All components follow a similar configuration pattern:

```kotlin hl_lines="4-8 12-16"
componentName {
  ComponentSystemOptions(
    // Container configuration
    container = ContainerOptions(
      registry = "docker.io",
      image = "component-image",
      tag = "version"
    ),
    
    // Expose configuration to your application
    configureExposedConfiguration = { cfg ->
      listOf(
        "app.component.host=${cfg.host}",
        "app.component.port=${cfg.port}"
      )
    }
  )
}
```

## Testcontainer vs Provided Instance

Each component supports two modes:

### Container Mode (Default)

Stove automatically manages testcontainers:

```kotlin
kafka {
  KafkaSystemOptions(
    container = KafkaContainerOptions(tag = "latest"),
    configureExposedConfiguration = { cfg -> listOf(...) }
  )
}
```

### Provided Instance Mode

Connect to existing infrastructure (useful for CI/CD):

```kotlin
kafka {
  KafkaSystemOptions.provided(
    bootstrapServers = "localhost:9092",
    configureExposedConfiguration = { cfg -> 
      listOf("kafka.bootstrapServers=${cfg.bootstrapServers}")
    }
  )
}
```

See [Provided Instances](11-provided-instances.md) for detailed documentation.

## Migrations Support

Database components support migrations:

```kotlin
class CreateTablesMigration : DatabaseMigration<PostgresSqlMigrationContext> {
  override val order: Int = 1
  
  override suspend fun execute(connection: PostgresSqlMigrationContext) {
    connection.operations.execute("CREATE TABLE ...")
  }
}

postgresql {
  PostgresqlOptions(...).migrations {
    register<CreateTablesMigration>()
  }
}
```

## Cleanup Support

All components support cleanup functions for data isolation:

```kotlin
couchbase {
  CouchbaseSystemOptions(
    defaultBucket = "bucket",
    cleanup = { cluster ->
      cluster.query("DELETE FROM `bucket` WHERE type = 'test'")
    },
    configureExposedConfiguration = { cfg ->
      listOf(
        "couchbase.hosts=${cfg.hostsWithPort}",
        "couchbase.username=${cfg.username}",
        "couchbase.password=${cfg.password}"
      )
    }
  )
}
```

## Best Practices

1. **Use random data** - Generate unique identifiers for each test to avoid conflicts
2. **Leverage cleanup functions** - Clean test data between runs
3. **Configure timeouts appropriately** - Set realistic timeouts for your environment
4. **Use the DSL consistently** - Leverage the fluent API for readable tests
5. **Combine components** - <span data-rn="highlight" data-rn-color="#00968855" data-rn-duration="800">Test complete workflows across multiple systems</span>

## Example: Full Stack Test

```kotlin hl_lines="7 12 19 26 31 36"
test("should process order end-to-end") {
  stove {
    val orderId = UUID.randomUUID().toString()
    
    // Mock payment service
    wiremock {
      mockPost("/payments", statusCode = 200, responseBody = PaymentResult(success = true).some())
    }
    
    // Create order via API
    http {
      postAndExpectBody<OrderResponse>("/orders", body = CreateOrderRequest(orderId).some()) { 
        it.status shouldBe 201 
      }
    }
    
    // Verify stored in database
    couchbase {
      shouldGet<Order>("orders", orderId) { order ->
        order.status shouldBe "CREATED"
      }
    }
    
    // Verify event published
    kafka {
      shouldBePublished<OrderCreatedEvent> { actual.orderId == orderId }
    }
    
    // Verify indexed for search
    elasticsearch {
      shouldGet<Order>(index = "orders", key = orderId) { it.status shouldBe "CREATED" }
    }
    
    // Verify cached
    redis {
      client().connect().sync().get("order:$orderId") shouldNotBe null
    }
  }
}
```

## Detailed Documentation

- [Couchbase](01-couchbase.md) - NoSQL document database with N1QL queries
- [Kafka](02-kafka.md) - Message streaming with producer/consumer testing
- [Elasticsearch](03-elasticsearch.md) - Search engine with query DSL support
- [WireMock](04-wiremock.md) - Mock external HTTP dependencies
- [gRPC Mock](14-grpc-mock.md) - Mock external gRPC services
- [HTTP Client](05-http.md) - Test your REST API endpoints
- [gRPC](12-grpc.md) - Test your gRPC services with Wire and grpc-kotlin
- [PostgreSQL](06-postgresql.md) - Relational database with SQL support
- [MongoDB](07-mongodb.md) - Document database with aggregation support
- [MSSQL](08-mssql.md) - Microsoft SQL Server support
- [Redis](09-redis.md) - In-memory data store for caching
- [Bridge](10-bridge.md) - Direct access to application beans
- [Provided Instances](11-provided-instances.md) - Use external infrastructure
- [Reporting](13-reporting.md) - Detailed execution reports and failure diagnostics
- [Tracing](15-tracing.md) - <span data-rn="underline" data-rn-color="#009688">Execution tracing with OpenTelemetry for full call chain visibility</span>
