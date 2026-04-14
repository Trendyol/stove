# System Setup Reference

## Contents
- [HTTP Client](#http-client)
- [PostgreSQL](#postgresql)
- [MySQL](#mysql)
- [MSSQL](#mssql)
- [Cassandra](#cassandra)
- [MongoDB](#mongodb)
- [Redis](#redis)
- [Elasticsearch](#elasticsearch)
- [Couchbase](#couchbase)
- [Kafka](#kafka)
- [WireMock](#wiremock)
- [gRPC Mock](#grpc-mock)
- [gRPC Client](#grpc-client)
- [Bridge](#bridge)
- [Dashboard](#dashboard)
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

## MySQL

```kotlin
mysql {
    MySqlSystemOptions(
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

Same migration pattern as PostgreSQL, using `MySqlMigrationContext`.

## MSSQL

```kotlin
mssql {
    MsSqlSystemOptions(
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

Same migration pattern, using `MsSqlMigrationContext`.

## Cassandra

```kotlin
cassandra {
    CassandraSystemOptions(
        keyspace = "my_keyspace",
        datacenter = "datacenter1",
        container = CassandraContainerOptions(tag = "4.1"),
        configureExposedConfiguration = { cfg ->
            listOf(
                "cassandra.host=${cfg.host}",
                "cassandra.port=${cfg.port}",
                "cassandra.keyspace=${cfg.keyspace}",
                "cassandra.datacenter=${cfg.datacenter}"
            )
        }
    ).migrations {
        register<CreateTableMigration>()
    }
}
```

Migration class:

```kotlin
class CreateTableMigration : CassandraMigration {
    override val order: Int = 1

    override suspend fun execute(connection: CassandraMigrationContext) {
        connection.session.execute(
            """
            CREATE TABLE IF NOT EXISTS orders (
                id text PRIMARY KEY,
                user_id text,
                amount double,
                status text
            );
            """.trimIndent()
        )
    }
}
```

For an externally managed Cassandra:

```kotlin
cassandra {
    CassandraSystemOptions.provided(
        host = "localhost",
        port = 9042,
        datacenter = "dc1",
        keyspace = "my_keyspace",
        configureExposedConfiguration = { cfg ->
            listOf("cassandra.contact-points=${cfg.host}:${cfg.port}")
        }
    )
}
```

Container operations: `pause()` / `unpause()` to simulate Cassandra downtime.

## MongoDB

```kotlin
mongodb {
    MongodbSystemOptions(
        databaseOptions = DatabaseOptions(
            default = DefaultDatabase(name = "testdb", collection = "orders")
        ),
        container = MongoContainerOptions(tag = "7.0"),
        configureExposedConfiguration = { cfg ->
            listOf(
                "mongodb.connection-string=${cfg.connectionString}",
                "mongodb.host=${cfg.host}",
                "mongodb.port=${cfg.port}"
            )
        }
    ).migrations {
        register<SeedDataMigration>()
    }
}
```

For an externally managed MongoDB:

```kotlin
mongodb {
    MongodbSystemOptions.provided(
        connectionString = "mongodb://localhost:27017",
        host = "localhost",
        port = 27017,
        configureExposedConfiguration = { cfg ->
            listOf("spring.data.mongodb.uri=${cfg.connectionString}")
        }
    )
}
```

Migration class uses `MongodbMigrationContext` with access to `client: MongoClient`.

Container operations: `pause()` / `unpause()`.

## Redis

```kotlin
redis {
    RedisOptions(
        database = 0,
        password = "redis-password",
        container = RedisContainerOptions(tag = "7-alpine"),
        configureExposedConfiguration = { cfg ->
            listOf(
                "redis.host=${cfg.host}",
                "redis.port=${cfg.port}",
                "redis.password=${cfg.password}"
            )
        }
    )
}
```

For an externally managed Redis:

```kotlin
redis {
    RedisOptions.provided(
        host = "localhost",
        port = 6379,
        password = "secret",
        database = 0,
        configureExposedConfiguration = { cfg ->
            listOf("spring.redis.url=${cfg.redisUri}")
        }
    )
}
```

Container operations: `pause()` / `unpause()`.

## Elasticsearch

```kotlin
elasticsearch {
    ElasticsearchSystemOptions(
        container = ElasticContainerOptions(
            tag = "8.15.0",
            password = "elastic-password",
            disableSecurity = true
        ),
        configureExposedConfiguration = { cfg ->
            listOf(
                "elasticsearch.host=${cfg.host}",
                "elasticsearch.port=${cfg.port}"
            )
        }
    ).migrations {
        register<CreateIndexMigration>()
    }
}
```

For an externally managed Elasticsearch:

```kotlin
elasticsearch {
    ElasticsearchSystemOptions.provided(
        host = "localhost",
        port = 9200,
        configureExposedConfiguration = { cfg ->
            listOf("es.url=http://${cfg.host}:${cfg.port}")
        }
    )
}
```

Migration class uses `ElasticsearchClient` as context directly.

Container operations: `pause()` / `unpause()`.

## Couchbase

```kotlin
couchbase {
    CouchbaseSystemOptions(
        defaultBucket = "test-bucket",
        containerOptions = CouchbaseContainerOptions(tag = "7.6.1"),
        configureExposedConfiguration = { cfg ->
            listOf(
                "couchbase.connection-string=${cfg.connectionString}",
                "couchbase.username=${cfg.username}",
                "couchbase.password=${cfg.password}"
            )
        }
    ).migrations {
        register<CreateBucketMigration>()
    }
}
```

For an externally managed Couchbase:

```kotlin
couchbase {
    CouchbaseSystemOptions.provided(
        connectionString = "couchbase://localhost",
        username = "admin",
        password = "password",
        defaultBucket = "test-bucket",
        configureExposedConfiguration = { cfg ->
            listOf("couchbase.hosts=${cfg.hostsWithPort}")
        }
    )
}
```

Migration class uses `Cluster` as context. Container operations: `pause()` / `unpause()`.

## Kafka

Use `stove-kafka` for standalone. Use `stove-spring-kafka` for Spring Boot Kafka listeners (`shouldBeConsumed`, `shouldBeFailed`, `shouldBeRetried`).

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

**Application-side requirements (Spring Boot Kafka)**:
- Inject `RecordInterceptor<String, String>` into your `ConcurrentKafkaListenerContainerFactory` and call `factory.setRecordInterceptor(interceptor)`.
- Register `TestSystemKafkaInterceptor<*, *>` and a `StoveSerde` bean in test dependencies.

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

Direct access to DI container from tests. Built into `stove-spring` / `stove-ktor` / `stove-micronaut`.

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

## Dashboard

Streams test events to the stove CLI for real-time visualization.

```kotlin
dashboard {
    DashboardSystemOptions(
        appName = "my-service",
        cliHost = "localhost",  // default
        cliPort = 4041          // default
    )
}
```

Run `stove` CLI separately, then run your tests — the dashboard at `http://localhost:4040` shows a live tree of specs, test hierarchy, timeline entries, traces, and snapshots.

## Reporting

Reporting and test hierarchy tracking require the framework extension. This is mandatory for Dashboard, tracing, and structured failure reports.

### Kotest

Register `StoveKotestExtension` in your `AbstractProjectConfig`:

```kotlin
class StoveConfig : AbstractProjectConfig() {
    override val extensions: List<Extension> = listOf(StoveKotestExtension())

    override suspend fun beforeProject() {
        Stove().with { /* systems */ }.run()
    }

    override suspend fun afterProject() {
        Stove.stop()
    }
}
```

Requires `stove-extensions-kotest` dependency and a `kotest.properties` file pointing to this config class.

### JUnit

Annotate your base test class with `@ExtendWith(StoveJUnitExtension::class)`:

```kotlin
@ExtendWith(StoveJUnitExtension::class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class BaseE2ETest {
    companion object {
        @JvmStatic @BeforeAll
        fun setup() = runBlocking {
            Stove().with { /* systems */ }.run()
        }

        @JvmStatic @AfterAll
        fun teardown() = runBlocking { Stove.stop() }
    }
}
```

Requires `stove-extensions-junit` dependency. Supports `@Nested` class hierarchy.

## Application runner

Goes last, after all systems. Systems inject configuration via `configureExposedConfiguration`.

### Spring Boot

```kotlin
springBoot(
    runner = { params ->
        com.yourcompany.yourapp.run(params) {
            addTestDependencies {
                bean<TestSystemKafkaInterceptor<*, *>>(isPrimary = true)
                bean { StoveSerde.jackson.anyByteArraySerde() }
            }
        }
    },
    withParameters = listOf(
        "server.port=8080",
        "grpc.server.port=$GRPC_SERVER_PORT"
    )
)
```

For Spring Boot 4.x, use `addTestDependencies4x` with `registerBean<>()`:

```kotlin
addTestDependencies4x {
    registerBean<TestSystemKafkaInterceptor<*, *>>(primary = true)
    registerBean { StoveSerde.jackson.anyByteArraySerde() }
}
```

### Ktor

```kotlin
ktor(
    runner = { params ->
        com.yourcompany.yourapp.run(params, wait = false)
    },
    withParameters = listOf("server.port=8080")
)
```

### Quarkus

```kotlin
quarkus(
    runner = { params ->
        com.yourcompany.yourapp.main(params)
    },
    withParameters = listOf("quarkus.http.port=8080")
)
```

Supports both direct main runner and packaged runtime. Configurable startup timeout via `stove.quarkus.startup.timeout.ms` system property (default: 120s).

### Micronaut

```kotlin
micronaut(
    runner = { params ->
        com.yourcompany.yourapp.run(params)
    },
    withParameters = listOf("micronaut.server.port=8080")
)
```

Returns `ApplicationContext`, enabling bridge/DI access.

## Keep dependencies running

Keeps containers alive between test runs for faster local iteration. Disable in CI.

```kotlin
Stove {
    keepDependenciesRunning()
}.with { /* systems */ }.run()
```
