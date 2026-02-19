# System Setup Reference

## Contents
- [HTTP Client](#http-client)
- [PostgreSQL](#postgresql)
- [Kafka](#kafka)
- [WireMock](#wiremock)
- [gRPC Mock](#grpc-mock)
- [gRPC Client](#grpc-client)
- [Bridge](#bridge)
- [Reporting](#reporting)
- [Application runner](#application-runner)
- [Keep dependencies running](#keep-dependencies-running)

All systems are configured inside `Stove().with { }`. The application runner goes last.

## HTTP Client

```kotlin
httpClient {
    HttpClientSystemOptions(baseUrl = "http://localhost:8080")
}
```

## PostgreSQL

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

For R2DBC:

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

Use `stove-kafka` for standalone. Use `stove-spring-kafka` for Spring Boot (adds `shouldBeConsumed`, `shouldBeFailed`, `shouldBeRetried`).

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

**Application-side requirement**: Inject `ConsumerAwareRecordInterceptor<String, String>` into your `ConcurrentKafkaListenerContainerFactory` and call `factory.setRecordInterceptor(interceptor)`.

## WireMock

```kotlin
wiremock {
    WireMockSystemOptions(
        port = 0, // Dynamic port — recommended for CI
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

All external service URLs must be configurable so they can be pointed to WireMock.

## gRPC Mock

```kotlin
grpcMock {
    GrpcMockSystemOptions(
        port = 0, // Dynamic port
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

For testing your own gRPC server (not external mocks):

```kotlin
grpc {
    GrpcSystemOptions(host = "localhost", port = 50051)
}
```

## Bridge

Direct access to DI container from tests. Built into `stove-spring` / `stove-ktor`.

```kotlin
bridge()  // Auto-detects DI framework
```

### Ktor DI support

Bridge auto-detects your DI framework:

```kotlin
// Koin — add io.insert-koin:koin-ktor to classpath
bridge()  // auto-detects Koin

// Ktor-DI — add io.ktor:ktor-server-di to classpath
bridge()  // auto-detects Ktor-DI

// Custom resolver (Kodein, Dagger, etc.)
bridge { application, type ->
    myDiContainer.resolve(type)
}
```

## Reporting

Enabled by default via `StoveKotestExtension()` or `StoveJUnitExtension`.

## Application runner

Goes last, after all systems. Systems inject configuration via `configureExposedConfiguration`.

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
        "grpc.server.port=$GRPC_SERVER_PORT"
    )
)
```

For Spring Boot 4.x, use `addTestDependencies4x` with `registerBean<>()`.

### Ktor

```kotlin
ktor(
    runner = { params ->
        com.yourcompany.yourapp.run(params, wait = false)
    },
    withParameters = listOf("server.port=8080")
)
```

## Keep dependencies running

Keeps containers alive between test runs for faster local iteration. Disable in CI.

```kotlin
Stove {
    keepDependenciesRunning()
}.with { /* systems */ }.run()
```
