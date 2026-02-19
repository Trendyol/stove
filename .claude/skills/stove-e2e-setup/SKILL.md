---
name: stove-e2e-setup
description: Sets up a complete end-to-end testing suite using the Stove framework for Spring Boot or Ktor applications. Configures test-e2e source set, Gradle dependencies, Stove systems (HTTP, PostgreSQL, Kafka, WireMock, gRPC), tracing, and test DSL. Use when creating e2e tests, adding Stove to a project, configuring Stove systems, or writing integration tests with Stove.
---

# Stove E2E Testing Suite Setup

Step-by-step guide for setting up end-to-end tests with [Stove](https://github.com/Trendyol/stove). Defaults to **Spring Boot + Kotest**; Ktor and JUnit variants noted where they differ.

## Prerequisites

- JDK 17+, Docker running, Kotlin 1.8+, Gradle (Kotlin DSL)

## Setup checklist

```
- [ ] Create test-e2e source set directory layout
- [ ] Configure Gradle (BOM, dependencies, source set, e2eTest task)
- [ ] Extract run() function from application entry point
- [ ] Create AbstractProjectConfig (Kotest) or base test class (JUnit)
- [ ] Create kotest.properties
- [ ] Configure systems (HTTP, PostgreSQL, Kafka, WireMock, gRPC, Bridge)
- [ ] Configure tracing (Gradle plugin + enableSpanReceiver())
- [ ] Write tests using stove {} DSL
```

## 1. Project structure

```
your-module/src/
  main/kotlin/                     # Application code
  test/kotlin/                     # Unit tests
  test-e2e/
    kotlin/com/yourcompany/yourapp/e2e/
      setup/
        TestConfig.kt              # AbstractProjectConfig
        InitialMigration.kt        # DB migrations (if PostgreSQL)
      tests/
        OrderE2ETest.kt            # Test files
    resources/
      kotest.properties            # Points to TestConfig
```

## 2. Gradle configuration

For detailed Gradle setup including source set registration, e2eTest task, and IDE integration, see [gradle-config.md](gradle-config.md).

### Dependencies (BOM)

```kotlin
dependencies {
    testImplementation(platform("com.trendyol:stove-bom:$stoveVersion"))
    testImplementation("com.trendyol:stove")
    testImplementation("com.trendyol:stove-spring")             // or stove-ktor
    testImplementation("com.trendyol:stove-extensions-kotest")  // or stove-extensions-junit

    // Add only what you need:
    testImplementation("com.trendyol:stove-http")
    testImplementation("com.trendyol:stove-postgres")
    testImplementation("com.trendyol:stove-kafka")
    testImplementation("com.trendyol:stove-wiremock")
    testImplementation("com.trendyol:stove-grpc")
    testImplementation("com.trendyol:stove-grpc-mock")
    testImplementation("com.trendyol:stove-tracing")
}
```

## 3. Prepare the application

Extract the entry point into a `run()` function so Stove can start your app from tests.

### Spring Boot

```kotlin
@SpringBootApplication
class MyApp

fun main(args: Array<String>) = run(args)

fun run(
    args: Array<String>,
    init: SpringApplication.() -> Unit = {}
): ConfigurableApplicationContext =
    runApplication<MyApp>(*args) { init() }
```

### Ktor (Koin)

```kotlin
fun run(args: Array<String>, wait: Boolean = true, testModules: List<Module> = emptyList()): Application {
    return embeddedServer(Netty, port = args.getPort()) {
        install(Koin) { modules(appModule, *testModules.toTypedArray()) }
        configureRouting()
    }.start(wait = wait).application
}
```

## 4. Stove configuration

### Kotest: AbstractProjectConfig

```kotlin
class TestConfig : AbstractProjectConfig() {
    override val extensions: List<Extension> = listOf(StoveKotestExtension())

    override suspend fun beforeProject() {
        Stove()
            .with {
                // Systems configured here — see Section 5
            }.run()
    }

    override suspend fun afterProject() {
        Stove.stop()
    }
}
```

### kotest.properties

Create `src/test-e2e/resources/kotest.properties`:

```properties
kotest.framework.config.fqn=com.yourcompany.yourapp.e2e.setup.TestConfig
```

### JUnit alternative

```kotlin
@ExtendWith(StoveJUnitExtension::class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class BaseE2ETest {
    companion object {
        @JvmStatic @BeforeAll
        fun setup() = runBlocking { Stove().with { /* systems */ }.run() }

        @JvmStatic @AfterAll
        fun teardown() = runBlocking { Stove.stop() }
    }
}
```

## 5. System setup

Configure systems inside `Stove().with { }`. For full configuration options and examples for each system, see [system-setup.md](system-setup.md).

```kotlin
Stove()
    .with {
        httpClient {
            HttpClientSystemOptions(baseUrl = "http://localhost:8080")
        }

        bridge()

        tracing { enableSpanReceiver() }

        wiremock {
            WireMockSystemOptions(
                configureExposedConfiguration = { cfg ->
                    listOf("payment.url=${cfg.baseUrl}", "inventory.url=${cfg.baseUrl}")
                }
            )
        }

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
            ).migrations { register<InitialMigration>() }
        }

        kafka {
            KafkaSystemOptions(
                serde = StoveSerde.jackson.anyByteArraySerde(),
                configureExposedConfiguration = { cfg ->
                    listOf(
                        "spring.kafka.bootstrap-servers=${cfg.bootstrapServers}",
                        "spring.kafka.producer.properties.interceptor.classes=${cfg.interceptorClass}",
                        "spring.kafka.consumer.properties.interceptor.classes=${cfg.interceptorClass}"
                    )
                }
            )
        }

        springBoot(
            runner = { params ->
                com.yourcompany.yourapp.run(params) {
                    addTestDependencies { bean<TestSystemInterceptor>(isPrimary = true) }
                }
            },
            withParameters = listOf("server.port=8080")
        )
    }.run()
```

## 6. Tracing

For full tracing configuration (Gradle plugin options, buildSrc approach, trace validation DSL), see [tracing.md](tracing.md).

Add inside `Stove().with { }`:

```kotlin
tracing { enableSpanReceiver() }
```

Attach the OpenTelemetry agent via the Gradle plugin:

```kotlin
plugins { id("com.trendyol.stove.tracing") version "$stoveVersion" }

stoveTracing {
    serviceName.set("my-service")
    testTaskNames.set(listOf("e2eTest"))
}
```

## 7. Writing tests

For comprehensive DSL examples (HTTP, PostgreSQL, Kafka, WireMock, gRPC, Bridge, multi-system), see [writing-tests.md](writing-tests.md).

### Basic pattern

```kotlin
class OrderE2ETest : FunSpec({
    test("should create order and publish event") {
        stove {
            wiremock {
                mockGet("/inventory/item-1", 200, InventoryResponse(true).some())
            }

            http {
                postAndExpectBody<OrderResponse>(
                    uri = "/orders",
                    body = CreateOrderRequest("user-${UUID.randomUUID()}", 99.99).some()
                ) { response ->
                    response.status shouldBe 201
                    response.body().status shouldBe "CONFIRMED"
                }
            }

            postgresql {
                shouldQuery<OrderRow>(
                    query = "SELECT * FROM orders WHERE user_id = '$userId'",
                    mapper = { row -> OrderRow(row.string("id"), row.string("status")) }
                ) { orders ->
                    orders.size shouldBe 1
                    orders.first().status shouldBe "CONFIRMED"
                }
            }

            kafka {
                shouldBePublished<OrderCreatedEvent>(10.seconds) {
                    actual.userId == userId
                }
            }
        }
    }
})
```

## 8. Writing your own Stove system

Stove is extensible. For the complete pattern with a working example (db-scheduler), see [custom-systems.md](custom-systems.md).

The pattern: implement `PluggedSystem` → create a listener/adapter → write `@StoveDsl` extensions for `WithDsl` (registration) and `ValidationDsl` (assertions) → register via `addTestDependencies`.

## 9. Best practices

- **Unique test data**: `UUID.randomUUID()` for every test, never hardcoded IDs
- **Single config**: Configure Stove once in `AbstractProjectConfig`, never per-test
- **Dynamic ports**: Use `port = 0` for WireMock/gRPC Mock (CI compatibility)
- **Test through APIs**: Call HTTP endpoints, verify DB state and events as side effects
- **No sleep**: Use `shouldBePublished<Event>(atLeastIn = 10.seconds) { ... }` instead of `Thread.sleep`
- **Local dev speed**: Use `Stove { keepDependenciesRunning() }.with { }.run()` to keep containers alive between runs

## Running tests

```bash
./gradlew e2eTest                                              # Run e2e tests
./gradlew e2eTest --tests "com.myapp.e2e.OrderE2ETest"        # Specific test
./gradlew test e2eTest                                         # Unit + e2e
```

## Reference

- [gradle-config.md](gradle-config.md) — Source set, e2eTest task, IDE integration
- [system-setup.md](system-setup.md) — All system configuration options
- [writing-tests.md](writing-tests.md) — Complete test DSL reference
- [tracing.md](tracing.md) — Tracing plugin and validation DSL
- [custom-systems.md](custom-systems.md) — Writing your own Stove system
- [Spring Showcase Recipe](../../../../recipes/kotlin-recipes/spring-showcase/) — Complete working example
