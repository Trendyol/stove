# System Setup Reference

All systems are configured inside the `Stove().with { }` block.

## HTTP Client

```kotlin
httpClient {
    HttpClientSystemOptions(
        baseUrl = "http://localhost:8080"
    )
}
```

## PostgreSQL (with migrations)

```kotlin
postgresql {
    PostgresqlOptions(
        databaseName = "testdb",
        configureExposedConfiguration = { cfg ->
            listOf(
                "spring.datasource.url=${cfg.jdbcUrl}",
                "spring.datasource.username=${cfg.username}",
                "spring.datasource.password=${cfg.password}"
            )
        }
    ).migrations {
        register<InitialMigration>()
    }
}
```

Migration class:

```kotlin
class InitialMigration : DatabaseMigration<PostgresSqlMigrationContext> {
    override val order: Int = 1

    override suspend fun execute(connection: PostgresSqlMigrationContext) {
        connection.operations.execute(
            """
            CREATE TABLE IF NOT EXISTS orders (
                id VARCHAR(255) PRIMARY KEY,
                user_id VARCHAR(255) NOT NULL,
                amount DECIMAL(10, 2) NOT NULL,
                status VARCHAR(50) NOT NULL,
                created_at TIMESTAMP DEFAULT NOW()
            );
            """.trimIndent()
        )
    }
}
```

For R2DBC (reactive):

```kotlin
configureExposedConfiguration = { cfg ->
    listOf(
        "spring.r2dbc.url=r2dbc:postgresql://${cfg.host}:${cfg.port}/testdb",
        "spring.r2dbc.username=${cfg.username}",
        "spring.r2dbc.password=${cfg.password}"
    )
}
```

## Kafka

### Standalone Kafka

```kotlin
kafka {
    KafkaSystemOptions(
        serde = StoveSerde.jackson.anyByteArraySerde(),
        configureExposedConfiguration = { cfg ->
            listOf(
                "kafka.bootstrapServers=${cfg.bootstrapServers}",
                "kafka.interceptorClasses=${cfg.interceptorClass}"
            )
        }
    )
}
```

### Spring Kafka (recommended for Spring Boot)

Use `stove-spring-kafka` for `shouldBeConsumed`, `shouldBeFailed`, `shouldBeRetried`.

```kotlin
kafka {
    KafkaSystemOptions(
        serde = StoveSerde.jackson.anyByteArraySerde(),
        valueSerializer = JsonSerializer(),
        containerOptions = KafkaContainerOptions(tag = "8.0.3") {
            withStartupAttempts(3)
        },
        configureExposedConfiguration = { cfg ->
            listOf(
                "spring.kafka.bootstrap-servers=${cfg.bootstrapServers}",
                "spring.kafka.producer.properties.interceptor.classes=${cfg.interceptorClass}",
                "spring.kafka.consumer.properties.interceptor.classes=${cfg.interceptorClass}"
            )
        }
    )
}
```

**Application-side**: Inject `ConsumerAwareRecordInterceptor<String, String>` into your `ConcurrentKafkaListenerContainerFactory` and call `factory.setRecordInterceptor(interceptor)`.

## WireMock

```kotlin
wiremock {
    WireMockSystemOptions(
        port = 0, // Dynamic port (recommended for CI)
        serde = StoveSerde.jackson.anyByteArraySerde(),
        configureExposedConfiguration = { cfg ->
            listOf(
                "payment.service.url=${cfg.baseUrl}",
                "inventory.service.url=${cfg.baseUrl}"
            )
        }
    )
}
```

All external service URLs in your application must be configurable so they can be pointed to WireMock.

## gRPC Mock

```kotlin
grpcMock {
    GrpcMockSystemOptions(
        port = GRPC_MOCK_PORT, // or 0 for dynamic
        configureExposedConfiguration = { cfg ->
            listOf(
                "grpcService.host=${cfg.host}",
                "grpcService.port=${cfg.port}"
            )
        }
    )
}
```

## gRPC Client

For testing your own gRPC server (not mocked external services):

```kotlin
grpc {
    GrpcSystemOptions(
        host = "localhost",
        port = 50051
    )
}
```

## Bridge

Direct access to your DI container from tests. Built into `stove-spring` / `stove-ktor`.

```kotlin
bridge()
```

## Reporting

Enabled by default via `StoveKotestExtension()` or `StoveJUnitExtension`. To configure:

```kotlin
Stove {
    reporting {
        enabled()
        dumpOnFailure()
    }
}.with { /* systems */ }.run()
```

## Application runner (goes last)

### Spring Boot

```kotlin
springBoot(
    runner = { params ->
        com.yourcompany.yourapp.run(params) {
            addTestDependencies {
                bean<TestSystemInterceptor>(isPrimary = true)
            }
        }
    },
    withParameters = listOf(
        "server.port=8080",
        "grpc.server.port=$GRPC_SERVER_PORT",
        "external-apis.fraud-detection.host=localhost",
        "external-apis.fraud-detection.port=$GRPC_MOCK_PORT"
    )
)
```

For Spring Boot 4.x, use `addTestDependencies4x` with `registerBean<>()` syntax.

### Ktor

```kotlin
ktor(
    runner = { params ->
        com.yourcompany.yourapp.run(params, wait = false)
    },
    withParameters = listOf("server.port=8080")
)
```

## Keep dependencies running (local dev)

```kotlin
Stove {
    keepDependenciesRunning()
}.with { /* systems */ }.run()
```

Keeps containers alive between test runs for faster iteration. Disable in CI.
