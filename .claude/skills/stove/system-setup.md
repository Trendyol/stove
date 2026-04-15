# System Setup Reference

## Contents
- [Process Application (non-JVM apps)](#process-application-non-jvm-apps)
- [Provided Application (smoke testing)](#provided-application-smoke-testing)
- [Keyed systems (multiple instances)](#keyed-systems-multiple-instances)
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
- [Migrations](#migrations)
- [Container customization](#container-customization)
- [Fault injection (pause/unpause)](#fault-injection-pauseunpause)
- [Serde configuration](#serde-configuration)
- [Cleanup](#cleanup)
- [Keep dependencies running](#keep-dependencies-running)

All systems are configured inside `Stove().with { }`. The application runner goes last.

## Process Application (non-JVM apps)

Use `processApp()` or `goApp()` from the `stove-process` module to test applications written in any language (Go, Python, Rust, Node.js, etc.) as OS processes.

```kotlin
dependencies {
    testImplementation("com.trendyol:stove-process")
}
```

### ProcessTarget variants

| Variant | Use case | Default readiness |
|---------|----------|-------------------|
| `ProcessTarget.Server(port, portEnvVar)` | HTTP/gRPC/TCP servers | HTTP GET `/health` |
| `ProcessTarget.Worker()` | Kafka consumers, batch jobs | 2s fixed delay |

### ReadinessStrategy variants

| Strategy | Use case |
|----------|----------|
| `ReadinessStrategy.HttpGet(HealthCheckOptions(...))` | REST APIs with health endpoint |
| `ReadinessStrategy.TcpPort(port)` | gRPC/TCP servers (no HTTP) |
| `ReadinessStrategy.Probe { ... }` | Custom readiness (file, DB, etc.) |
| `ReadinessStrategy.FixedDelay(duration)` | Simple workers |

### Configuration passing

Stove collects all system configurations (`configureExposedConfiguration` from each system) as `key=value` strings and passes them to the process. Two mechanisms are available — use one or both:

#### envMapper — environment variables

Maps Stove config keys to OS environment variables:

```kotlin
envMapper {
    "stove.config.key" to "ENV_VAR_NAME"    // map Stove config → env var
    env("STATIC_VAR", "value")              // static env var
    env("COMPUTED_VAR") { computeValue() }  // computed env var
}
```

#### argsMapper — CLI arguments

Maps Stove config keys to command-line arguments, appended to the process command:

```kotlin
// GNU-style: --db-host=localhost --db-port=5432
argsMapper(prefix = "--", separator = "=") {
    "database.host" to "db-host"
    "database.port" to "db-port"
    arg("verbose")                          // boolean flag: --verbose
    arg("log-level", "debug")               // static: --log-level=debug
    arg("config-file") { "/tmp/test.yaml" } // computed: --config-file=/tmp/test.yaml
}

// POSIX-style: -h localhost -p 5432 (space separator → two separate args)
argsMapper(prefix = "-", separator = " ") {
    "database.host" to "h"
    "database.port" to "p"
}

// No prefix: db-host=localhost
argsMapper(prefix = "", separator = "=") {
    "database.host" to "db-host"
}
```

#### Using both together

```kotlin
processApp {
    ProcessApplicationOptions(
        command = listOf("/path/to/server"),
        target = ProcessTarget.Server(port = 8090),
        envProvider = envMapper {
            "database.host" to "DB_HOST"         // passed as env var
        },
        argsProvider = argsMapper(prefix = "--", separator = "=") {
            "database.port" to "db-port"         // passed as --db-port=5432
            arg("verbose")
        }
    )
}
```

### Examples

```kotlin
// HTTP API (Go) — env vars
goApp(
    target = ProcessTarget.Server(port = 8090, portEnvVar = "APP_PORT"),
    envProvider = envMapper {
        "database.host" to "DB_HOST"
        "database.port" to "DB_PORT"
        env("LOG_LEVEL", "debug")
    }
)

// Rust CLI server — CLI args
processApp {
    ProcessApplicationOptions(
        command = listOf("/path/to/rust-server"),
        target = ProcessTarget.Server(port = 8090),
        argsProvider = argsMapper(prefix = "--", separator = "=") {
            "database.host" to "db-host"
            "database.port" to "db-port"
        }
    )
}

// gRPC server (any language)
processApp {
    ProcessApplicationOptions(
        command = listOf("/path/to/grpc-server"),
        target = ProcessTarget.Server(
            port = 50051,
            portEnvVar = "GRPC_PORT",
            readiness = ReadinessStrategy.TcpPort(port = 50051),
        ),
        envProvider = envMapper { "database.host" to "DB_HOST" }
    )
}

// Kafka consumer (no port)
goApp(
    target = ProcessTarget.Worker(readiness = ReadinessStrategy.FixedDelay(3.seconds)),
    envProvider = envMapper { "kafka.bootstrapServers" to "KAFKA_BROKERS" }
)
```

Key points:
- `goApp()` defaults binary path from `go.app.binary` system property
- Port env var is injected automatically for `Server` targets
- `envProvider` and `argsProvider` can be used independently or together
- No `bridge()` — the app runs as a separate process, no DI access
- See [other-languages.md](other-languages.md) and [go-setup.md](go-setup.md) for full setup guides

## Provided Application (smoke testing)

Use `providedApplication()` instead of a JVM runner to test against an already-deployed application. The application can be written in **any language** — Go, Python, .NET, Rust, Node.js, etc.

```kotlin
Stove().with {
    httpClient {
        HttpClientSystemOptions(baseUrl = "https://staging.myapp.com")
    }
    providedApplication {
        ProvidedApplicationOptions(
            healthCheck = HealthCheckOptions(
                url = "https://staging.myapp.com/health",
                retries = 10,
                retryDelay = 1.seconds,
                timeout = 30.seconds,
                expectedStatusCodes = setOf(200)
            )
        )
    }
}.run()
```

Without health check (fire-and-forget):

```kotlin
providedApplication()  // No health check, no options
```

Combine with `.provided()` system options to connect to existing infrastructure:

```kotlin
Stove().with {
    httpClient {
        HttpClientSystemOptions(baseUrl = "https://staging.myapp.com")
    }
    postgresql(AppDb) {
        PostgresqlOptions.provided(
            jdbcUrl = "jdbc:postgresql://staging-db:5432/myapp",
            cleanup = { ops -> ops.execute("DELETE FROM orders WHERE test_data = true") },
            configureExposedConfiguration = { listOf() }
        )
    }
    redis(CacheCluster) {
        RedisOptions.provided(
            host = "staging-redis", port = 6379,
            configureExposedConfiguration = { listOf() }
        )
    }
    providedApplication {
        ProvidedApplicationOptions(
            healthCheck = HealthCheckOptions(url = "https://staging.myapp.com/health")
        )
    }
}.run()
```

**Important**: `Bridge` (DI access via `using<T>`) is **not available** with `providedApplication()` — there is no local DI container. Use `cleanup` lambdas to manage test data on external infrastructure.

## Keyed systems (multiple instances)

Register multiple instances of the same system type using `SystemKey`. Define keys as singleton objects:

```kotlin
object AppDb : SystemKey
object AnalyticsDb : SystemKey
object PaymentService : SystemKey
object InventoryService : SystemKey
```

Use keys in registration:

```kotlin
Stove().with {
    // Two separate PostgreSQL containers with independent configs
    postgresql(AppDb) {
        PostgresqlOptions(
            databaseName = "appdb",
            configureExposedConfiguration = { cfg ->
                listOf("app.datasource.url=${cfg.jdbcUrl}")
            }
        ).migrations { register<AppSchemaMigration>() }
    }
    postgresql(AnalyticsDb) {
        PostgresqlOptions(
            databaseName = "analyticsdb",
            configureExposedConfiguration = { cfg ->
                listOf("analytics.datasource.url=${cfg.jdbcUrl}")
            }
        ).migrations { register<AnalyticsSchemaMigration>() }
    }

    // Two HTTP clients pointing to different services
    httpClient(PaymentService) {
        HttpClientSystemOptions(baseUrl = "https://pay.internal")
    }
    httpClient(InventoryService) {
        HttpClientSystemOptions(baseUrl = "https://inventory.internal")
    }

    // Two WireMock instances for different external APIs
    wiremock(PaymentService) {
        WireMockSystemOptions(port = 0, configureExposedConfiguration = { cfg ->
            listOf("payment.url=${cfg.baseUrl}")
        })
    }
    wiremock(InventoryService) {
        WireMockSystemOptions(port = 0, configureExposedConfiguration = { cfg ->
            listOf("inventory.url=${cfg.baseUrl}")
        })
    }

    springBoot(runner = { params -> run(params) })
}.run()
```

All systems support keyed registration: PostgreSQL, MySQL, MSSQL, Cassandra, MongoDB, Redis, Elasticsearch, Couchbase, Kafka (core), WireMock, gRPC, gRPC Mock, HTTP.

Each keyed instance gets:
- Its own container with a unique dynamic port (no conflicts)
- Independent `configureExposedConfiguration` — all configs are aggregated and passed to the AUT
- Isolated state storage (separate lock files per key)
- Its own `cleanup` lambda
- Distinct reporting name (e.g., `"PostgreSQL [AppDb]"`)

A single key can be shared across protocol types:

```kotlin
object PaymentService : SystemKey

httpClient(PaymentService) { HttpClientSystemOptions(baseUrl = "...") }
grpc(PaymentService) { GrpcSystemOptions(host = "...", port = 50051) }
wiremock(PaymentService) { WireMockSystemOptions(...) }
```

Keyed systems work with both Testcontainers and `.provided()` (external) instances:

```kotlin
postgresql(AppDb) {
    PostgresqlOptions.provided(
        jdbcUrl = "jdbc:postgresql://staging-db:5432/app",
        configureExposedConfiguration = { listOf() }
    )
}
```

## HTTP Client

```kotlin
httpClient {
    HttpClientSystemOptions(baseUrl = "http://localhost:8080")
}
```

Advanced options:

```kotlin
httpClient {
    HttpClientSystemOptions(
        baseUrl = "http://localhost:8080",
        timeout = 60.seconds,                                          // Request timeout (default: 30s)
        contentConverter = JacksonConverter(myObjectMapper),            // Custom JSON converter
        configureClient = {                                            // Ktor HttpClient config
            install(Logging) { level = LogLevel.ALL }
        },
        configureWebSocket = {                                         // WebSocket config
            pingIntervalMillis = 10_000
        },
        createClient = { url -> myCustomHttpClient(url) }             // Full client factory override
    )
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

For embedded Kafka (no Docker container):

```kotlin
kafka {
    KafkaSystemOptions(
        useEmbeddedKafka = true,
        configureExposedConfiguration = { cfg ->
            listOf("spring.kafka.bootstrap-servers=${cfg.bootstrapServers}")
        }
    )
}
```

**Application-side requirements (Spring Boot Kafka)**:
- Inject `RecordInterceptor<String, String>` into your `ConcurrentKafkaListenerContainerFactory` and call `factory.setRecordInterceptor(interceptor)`.
- Register `TestSystemKafkaInterceptor<*, *>` and a `StoveSerde` bean in test dependencies.

### Test-friendly Kafka settings

Default Kafka producer/consumer settings are tuned for production throughput, not test speed. In e2e tests, this causes timeouts, flaky assertions, and slow feedback. Configure for **immediate delivery and fast commits**:

**Container-level** — enable auto-topic creation so topics exist when producers/consumers first connect:

```kotlin
kafka {
    KafkaSystemOptions(
        containerOptions = KafkaContainerOptions(tag = "8.0.3") {
            withEnv("KAFKA_AUTO_CREATE_TOPICS_ENABLE", "true")
        },
        configureExposedConfiguration = { cfg ->
            listOf("spring.kafka.bootstrap-servers=${cfg.bootstrapServers}")
        }
    )
}
```

**Producer settings** — flush immediately, don't batch:

```properties
# Spring Boot application.yml or exposed via Stove config
spring.kafka.producer.properties.linger.ms=0          # Send immediately, don't wait to batch
spring.kafka.producer.properties.batch.size=1          # Single-message batches
spring.kafka.producer.acks=all                         # Wait for all replicas (reliable in single-broker test)
```

**Consumer settings** — commit fast, start from beginning, short timeouts:

```properties
spring.kafka.consumer.auto-offset-reset=earliest            # Start from beginning (don't miss messages)
spring.kafka.consumer.properties.auto.commit.interval.ms=100  # Commit offsets every 100ms (default: 5000ms)
spring.kafka.consumer.properties.max.poll.interval.ms=10000   # Shorter poll timeout (default: 300000ms)
spring.kafka.consumer.properties.session.timeout.ms=10000     # Faster rebalance on failure (default: 45000ms)
spring.kafka.consumer.properties.heartbeat.interval.ms=3000   # Faster heartbeat (default: 3000ms, keep ≤ session/3)
```

**Why this matters for Stove assertions:**

- `shouldBePublished` checks the Stove interceptor sink — messages must reach it promptly. `linger.ms=0` and `batch.size=1` prevent the producer from holding messages.
- `shouldBeConsumed` checks that the message was consumed AND its offset committed. `auto.commit.interval.ms=100` makes committed offsets visible within 100ms instead of the 5-second default.
- `shouldBeFailed` / `shouldBeRetried` check error and retry sinks — short `max.poll.interval.ms` prevents long waits before Kafka considers a consumer dead.
- Without `auto-offset-reset=earliest`, consumers joining after a message is produced will never see it, causing `shouldBeConsumed` to timeout.

**Passing these via Stove's `configureExposedConfiguration`:**

```kotlin
kafka {
    KafkaSystemOptions(
        containerOptions = KafkaContainerOptions {
            withEnv("KAFKA_AUTO_CREATE_TOPICS_ENABLE", "true")
        },
        configureExposedConfiguration = { cfg ->
            listOf(
                "spring.kafka.bootstrap-servers=${cfg.bootstrapServers}",
                "spring.kafka.producer.properties.interceptor.classes=${cfg.interceptorClass}",
                "spring.kafka.consumer.properties.interceptor.classes=${cfg.interceptorClass}",
                // Test-friendly overrides
                "spring.kafka.producer.properties.linger.ms=0",
                "spring.kafka.producer.properties.batch.size=1",
                "spring.kafka.consumer.auto-offset-reset=earliest",
                "spring.kafka.consumer.properties.auto.commit.interval.ms=100"
            )
        }
    )
}
```

**Non-Spring JVM apps** — the same principles apply. Pass equivalent properties through your app's configuration mechanism:

| Setting | Production default | Test-friendly value | Why |
|---------|-------------------|--------------------|----|
| `linger.ms` | 5-100 | `0` | Immediate send |
| `batch.size` | 16384 | `1` | No batching |
| `auto.commit.interval.ms` | 5000 | `100` | Fast offset visibility |
| `auto.offset.reset` | `latest` | `earliest` | Don't miss messages |
| `max.poll.interval.ms` | 300000 | `10000` | Faster failure detection |
| `auto.create.topics.enable` (broker) | `true` | `true` | Topics exist on first use |

**Go applications** — see [go-setup.md](go-setup.md) for per-library settings (sarama, franz-go, segmentio/kafka-go) including auto-topic creation, batch timeouts, commit intervals, and the separate producer/consumer client pattern for franz-go.

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

Streams test events to the stove CLI for real-time visualization. Requires `stove-dashboard` and `stove-extensions-kotest` or `stove-extensions-junit` — see [Reporting](#reporting).

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

## Migrations

Database and infrastructure systems support migrations that run after the system starts and before tests execute. Use for schema creation, indexing, and seed data.

```kotlin
postgresql {
    PostgresqlOptions(
        configureExposedConfiguration = { cfg -> listOf("spring.datasource.url=${cfg.jdbcUrl}") }
    ).migrations {
        register<CreateTablesMigration>()
        register<SeedDataMigration>()
    }
}
```

Migration class:

```kotlin
class CreateTablesMigration : PostgresqlMigration {
    override val order: Int = MigrationPriority.HIGHEST.value  // Runs first

    override suspend fun execute(connection: PostgresSqlMigrationContext) {
        connection.operations.execute("CREATE TABLE IF NOT EXISTS orders (...)")
    }
}

class SeedDataMigration : PostgresqlMigration {
    override val order: Int = 100  // After schema

    override suspend fun execute(connection: PostgresSqlMigrationContext) {
        connection.operations.execute("INSERT INTO orders ...")
    }
}
```

Ordering: migrations execute in ascending `order`. Use `MigrationPriority.HIGHEST` (schema), `MigrationPriority.LOWEST` (final setup), or custom integers.

Type aliases per system (use instead of `DatabaseMigration<XyzContext>`):

| Module | Type Alias | Context Type |
|---|---|---|
| stove-postgres | `PostgresqlMigration` | `PostgresSqlMigrationContext` |
| stove-mysql | `MySqlMigration` | `MySqlMigrationContext` |
| stove-mssql | `MsSqlMigration` | `SqlMigrationContext` |
| stove-mongodb | `MongodbMigration` | `MongodbMigrationContext` |
| stove-couchbase | `CouchbaseMigration` | `Cluster` |
| stove-elasticsearch | `ElasticsearchMigration` | `ElasticsearchClient` |
| stove-redis | `RedisMigration` | `RedisMigrationContext` |
| stove-kafka | `KafkaMigration` | `KafkaMigrationContext` |
| stove-cassandra | `CassandraMigration` | `CassandraMigrationContext` |

Advanced patterns:

```kotlin
// Factory function for migrations with parameters
.migrations {
    register<ConfigurableMigration> {
        ConfigurableMigration(batchSize = 1000)
    }
}

// Replace a migration with a test-specific override
.migrations {
    register<ProductionSeedMigration>()
    replace<ProductionSeedMigration, TestSeedMigration>()

    // Or replace with a factory
    replace<ProductionSeedMigration> {
        MinimalSeedMigration()
    }
}
```

Notes: migrations must have no-arg constructors (unless using factory registration). Use idempotent statements (`IF NOT EXISTS`). Don't close the connection — Stove manages it.

## Container customization

All container-backed systems accept a `ContainerOptions` with customization hooks:

```kotlin
postgresql {
    PostgresqlOptions(
        container = PostgresqlContainerOptions(
            registry = "my-registry.example.com",   // Custom Docker registry
            image = "postgres",                       // Image name
            tag = "16-alpine",                        // Image tag
            compatibleSubstitute = "my-registry.example.com/postgres",  // Alternative image
            containerFn = {                           // Customize container before startup
                withEnv("POSTGRES_INITDB_ARGS", "--encoding=UTF-8")
                withCommand("postgres", "-c", "max_connections=200")
            }
        ),
        configureExposedConfiguration = { cfg -> listOf("spring.datasource.url=${cfg.jdbcUrl}") }
    )
}

kafka {
    KafkaSystemOptions(
        containerOptions = KafkaContainerOptions(tag = "8.0.3") {
            withStartupAttempts(3)
            withEnv("KAFKA_AUTO_CREATE_TOPICS_ENABLE", "true")
        },
        configureExposedConfiguration = { cfg -> listOf("kafka.bootstrap=${cfg.bootstrapServers}") }
    )
}
```

The `containerFn` lambda receives the container instance before startup, so you can call any Testcontainers method (env vars, commands, exposed ports, volume mounts, etc.).

## Fault injection (pause/unpause)

All container-backed systems support `pause()` / `unpause()` for simulating outages. Both are idempotent.

```kotlin
stove {
    postgresql { pause() }

    http {
        getResponse<Any>("/health") { response ->
            response.status shouldBe 503
        }
    }

    postgresql { unpause() }

    http {
        getResponse<Any>("/health") { response ->
            response.status shouldBe 200
        }
    }
}
```

Supported: PostgreSQL, MySQL, MSSQL, Cassandra, MongoDB, Redis, Elasticsearch, Couchbase, Kafka.

## Serde configuration

Stove uses `StoveSerde` for JSON handling across systems (Kafka, MongoDB, WireMock, HTTP). Three implementations are built in:

```kotlin
// Jackson (default) — configure ObjectMapper
val serde = StoveSerde.jackson.anyByteArraySerde()
val stringSerde = StoveSerde.jackson.anyJsonStringSerde()

// With custom ObjectMapper
val customSerde = StoveSerde.jackson.anyByteArraySerde(
    StoveSerde.jackson.byConfiguring {
        disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        enable(MapperFeature.ACCEPT_CASE_INSENSITIVE_ENUMS)
    }
)

// Kotlinx Serialization (requires @Serializable)
val kotlinxSerde = StoveSerde.kotlinx.anyByteArraySerde()
val customKotlinx = StoveSerde.kotlinx.anyByteArraySerde(
    StoveSerde.kotlinx.byConfiguring {
        ignoreUnknownKeys = true
        isLenient = true
    }
)

// Gson
val gsonSerde = StoveSerde.gson.anyByteArraySerde()
val customGson = StoveSerde.gson.anyByteArraySerde(
    StoveSerde.gson.byConfiguring {
        setPrettyPrinting()
        serializeNulls()
    }
)
```

**Important: align Stove's serde with your application's serialization.** If your application uses a custom ObjectMapper (e.g., with snake_case naming, custom date formats, or extra modules), Stove must use the same configuration. Otherwise, Stove will fail to deserialize responses from your HTTP endpoints, Kafka messages produced by your app, or documents written to MongoDB/Elasticsearch/Couchbase. The same applies if your application uses Kotlinx Serialization or Gson — use the matching `StoveSerde` variant.

```kotlin
// Reuse your application's ObjectMapper so ser/de behavior matches
val appObjectMapper = MyApp.objectMapper()

httpClient {
    HttpClientSystemOptions(
        baseUrl = "http://localhost:8080",
        contentConverter = JacksonConverter(appObjectMapper)
    )
}

kafka {
    KafkaSystemOptions(
        serde = StoveSerde.jackson.anyByteArraySerde(appObjectMapper),
        configureExposedConfiguration = { cfg -> listOf("kafka.bootstrap=${cfg.bootstrapServers}") }
    )
}

mongodb {
    MongodbSystemOptions(
        serde = StoveSerde.jackson.anyJsonStringSerde(appObjectMapper),
        configureExposedConfiguration = { cfg -> listOf("mongodb.uri=${cfg.connectionString}") }
    )
}

wiremock {
    WireMockSystemOptions(
        serde = StoveSerde.jackson.anyByteArraySerde(appObjectMapper),
        configureExposedConfiguration = { cfg -> listOf("service.url=${cfg.baseUrl}") }
    )
}
```

## Cleanup

Every system accepts a `cleanup` lambda in its options. This runs during `Stove.stop()` (after all tests complete) and receives the system's native client. Use it to wipe test data — especially important for provided (external) instances that persist between runs.

```kotlin
postgresql {
    PostgresqlOptions(
        databaseName = "testdb",
        cleanup = { ops -> ops.execute("TRUNCATE orders, users") },
        configureExposedConfiguration = { cfg -> listOf("spring.datasource.url=${cfg.jdbcUrl}") }
    )
}

mongodb {
    MongodbSystemOptions(
        cleanup = { client -> client.getDatabase("testdb").drop() },
        configureExposedConfiguration = { cfg -> listOf("mongodb.uri=${cfg.connectionString}") }
    )
}

kafka {
    KafkaSystemOptions(
        cleanup = { admin ->
            val topics = admin.listTopics().names().get().filter { it.startsWith("test-") }
            if (topics.isNotEmpty()) admin.deleteTopics(topics).all().get()
        },
        configureExposedConfiguration = { cfg -> listOf("kafka.bootstrap=${cfg.bootstrapServers}") }
    )
}

redis {
    RedisOptions(
        cleanup = { client -> client.connect().sync().flushdb() },
        configureExposedConfiguration = { cfg -> listOf("redis.host=${cfg.host}") }
    )
}

elasticsearch {
    ElasticsearchSystemOptions(
        cleanup = { client -> client.indices().delete { it.index("test-*") } },
        configureExposedConfiguration = { cfg -> listOf("es.host=${cfg.host}") }
    )
}

couchbase {
    CouchbaseSystemOptions(
        cleanup = { cluster -> cluster.query("DELETE FROM `test-bucket`") },
        configureExposedConfiguration = { cfg -> listOf("cb.conn=${cfg.connectionString}") }
    )
}

cassandra {
    CassandraSystemOptions(
        cleanup = { session -> session.execute("TRUNCATE my_keyspace.orders") },
        configureExposedConfiguration = { cfg -> listOf("cassandra.host=${cfg.host}") }
    )
}

mysql {
    MySqlSystemOptions(
        cleanup = { ops -> ops.execute("TRUNCATE orders") },
        configureExposedConfiguration = { cfg -> listOf("spring.datasource.url=${cfg.jdbcUrl}") }
    )
}

mssql {
    MsSqlSystemOptions(
        cleanup = { ops -> ops.execute("TRUNCATE TABLE orders") },
        configureExposedConfiguration = { cfg -> listOf("spring.datasource.url=${cfg.jdbcUrl}") }
    )
}
```

Cleanup client types per system:

| System | Cleanup parameter type |
|---|---|
| PostgreSQL | `NativeSqlOperations` |
| MySQL | `NativeSqlOperations` |
| MSSQL | `NativeSqlOperations` |
| Cassandra | `CqlSession` |
| MongoDB | `MongoClient` |
| Redis | `RedisClient` |
| Elasticsearch | `ElasticsearchClient` |
| Couchbase | `Cluster` |
| Kafka | `Admin` |

WireMock uses event-driven cleanup instead:

```kotlin
wiremock {
    WireMockSystemOptions(
        removeStubAfterRequestMatched = true,  // Auto-remove stubs after match
        afterStubRemoved = { serveEvent, stubLog -> /* optional callback */ },
        configureExposedConfiguration = { cfg -> listOf("service.url=${cfg.baseUrl}") }
    )
}
```

The `cleanup` lambda also works with `.provided()` (external instances):

```kotlin
postgresql {
    PostgresqlOptions.provided(
        jdbcUrl = "jdbc:postgresql://localhost:5432/testdb",
        host = "localhost",
        port = 5432,
        cleanup = { ops -> ops.execute("TRUNCATE orders, users") },
        configureExposedConfiguration = { cfg -> listOf("spring.datasource.url=${cfg.jdbcUrl}") }
    )
}
```

## Keep dependencies running

Keeps containers alive between test runs for faster local iteration. Disable in CI.

```kotlin
Stove {
    keepDependenciesRunning()
}.with { /* systems */ }.run()
```
