---
name: stove-e2e-setup
description: Use when adding Stove e2e tests to a project, creating a test-e2e source set, configuring Stove systems (HTTP, PostgreSQL, Kafka, WireMock, gRPC), setting up the stove {} test DSL, enabling OpenTelemetry tracing for tests, writing AbstractProjectConfig, or extending Stove with custom systems.
---

# Setting Up Stove E2E Tests

Copy this checklist and track progress:

```
Setup Progress:
- [ ] Step 1: Create test-e2e source set layout
- [ ] Step 2: Configure Gradle (BOM, source set, e2eTest task)
- [ ] Step 3: Extract run() function from application entry point
- [ ] Step 4: Create StoveConfig (AbstractProjectConfig)
- [ ] Step 5: Create kotest.properties (Kotest only)
- [ ] Step 6: Configure systems inside Stove().with { }
- [ ] Step 7: Configure tracing (optional)
- [ ] Step 8: Write tests using stove {} DSL
```

Important: Stove e2e tests are Kotlin-first. Even if your application is Java/Scala, keep e2e tests under `src/test-e2e/kotlin` and write Stove setup/tests in Kotlin.

## Step 1: Project structure

```
your-module/src/
  main/(kotlin|java)/
  test/(kotlin|java)/
  test-e2e/
    kotlin/com/yourcompany/yourapp/e2e/
      setup/
        StoveConfig.kt
        InitialMigration.kt
      tests/
        OrderE2ETest.kt
    resources/
      kotest.properties
```

## Step 2: Gradle configuration

For source set registration, e2eTest task, and IDE integration details, see [gradle-config.md](gradle-config.md).

Add dependencies using the BOM:

```kotlin
dependencies {
    testImplementation(platform("com.trendyol:stove-bom:$stoveVersion"))
    testImplementation("com.trendyol:stove")
    testImplementation("com.trendyol:stove-spring")
    testImplementation("com.trendyol:stove-extensions-kotest")

    // Add only what you need:
    testImplementation("com.trendyol:stove-http")
    testImplementation("com.trendyol:stove-postgres")
    testImplementation("com.trendyol:stove-kafka")
    testImplementation("com.trendyol:stove-spring-kafka")
    testImplementation("com.trendyol:stove-wiremock")
    testImplementation("com.trendyol:stove-grpc")
    testImplementation("com.trendyol:stove-grpc-mock")
    testImplementation("com.trendyol:stove-tracing")
}
```

For Ktor, replace `stove-spring` with `stove-ktor`. For JUnit, replace `stove-extensions-kotest` with `stove-extensions-junit` and skip Step 5.

If you are unsure about Stove API names/signatures, verify from local downloaded artifacts (Gradle cache or Maven local repo) before writing code. See [gradle-config.md](gradle-config.md#resolve-api-ambiguity-from-local-artifacts).

## Step 3: Extract run()

Stove starts your application from tests. Extract the entry point:

```kotlin
// src/main/kotlin/.../MyApp.kt
@SpringBootApplication
class MyApp

fun main(args: Array<String>) = run(args)

fun run(
    args: Array<String>,
    init: SpringApplication.() -> Unit = {}
): ConfigurableApplicationContext =
    runApplication<MyApp>(*args) { init() }
```

## Step 4: StoveConfig

```kotlin
class StoveConfig : AbstractProjectConfig() {
    override val extensions: List<Extension> = listOf(StoveKotestExtension())

    override suspend fun beforeProject() {
        Stove()
            .with {
                // Systems go here — see Step 6
            }.run()
    }

    override suspend fun afterProject() {
        Stove.stop()
    }
}
```

For JUnit, see [gradle-config.md](gradle-config.md) for the `BaseE2ETest` pattern.

## Step 5: kotest.properties (Kotest only)

Create `src/test-e2e/resources/kotest.properties`:

```properties
kotest.framework.config.fqn=com.yourcompany.yourapp.e2e.setup.StoveConfig
```

## Step 6: Configure systems

Configure inside `Stove().with { }`. For all options per system, see [system-setup.md](system-setup.md).

```kotlin
Stove()
    .with {
        httpClient {
            HttpClientSystemOptions(baseUrl = "http://localhost:8080")
        }

        bridge()

        // Optional (requires com.trendyol:stove-tracing)
        tracing { enableSpanReceiver() }

        wiremock {
            WireMockSystemOptions(
                configureExposedConfiguration = { cfg ->
                    listOf("payment.url=${cfg.baseUrl}")
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

        // Application runner goes last
        springBoot(
            runner = { params ->
                com.yourcompany.yourapp.run(params) {
                    addTestDependencies {
                        bean<TestSystemKafkaInterceptor<*, *>>(isPrimary = true)
                        bean { StoveSerde.jackson.anyByteArraySerde() }
                    }
                }
            },
            withParameters = listOf("server.port=8080")
        )
    }.run()
```

For Spring Boot 4.x, use:

```kotlin
addTestDependencies4x {
    registerBean<TestSystemKafkaInterceptor<*, *>>(primary = true)
    registerBean { StoveSerde.jackson.anyByteArraySerde() }
}
```

## Step 7: Tracing (optional)

For full plugin options, buildSrc alternative, and trace validation DSL, see [tracing.md](tracing.md).

```kotlin
plugins { id("com.trendyol.stove.tracing") version "$stoveVersion" }

stoveTracing {
    serviceName.set("my-service")
    testTaskNames.set(listOf("e2eTest"))
}
```

## Step 8: Write tests

For the complete DSL reference (HTTP, PostgreSQL, Kafka, WireMock, gRPC Mock, gRPC Client, Bridge, multi-system examples), see [writing-tests.md](writing-tests.md).

```kotlin
class OrderE2ETest : FunSpec({
    test("should create order and publish event") {
        stove {
            val userId = "user-${UUID.randomUUID()}"

            wiremock {
                mockGet("/inventory/item-1", 200, InventoryResponse(true).some())
            }

            http {
                postAndExpectBody<OrderResponse>(
                    uri = "/orders",
                    body = CreateOrderRequest(userId, 99.99).some()
                ) { response ->
                    response.status shouldBe 201
                }
            }

            postgresql {
                shouldQuery<OrderRow>(
                    query = "SELECT * FROM orders WHERE user_id = '$userId'",
                    mapper = { row -> OrderRow(row.string("id"), row.string("status")) }
                ) { it.size shouldBe 1 }
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

## Writing custom Stove systems

Stove is extensible. For the complete pattern with a working db-scheduler example, see [custom-systems.md](custom-systems.md).

## Best practices

- Generate unique IDs per test: `UUID.randomUUID()`
- Configure Stove once in `AbstractProjectConfig`, never per-test
- Keep e2e tests in `src/test-e2e/kotlin` (also for Java/Scala applications)
- If API is ambiguous, inspect local `stove-*.jar` / `stove-*-sources.jar` in Gradle/Maven caches and confirm class/method names before coding
- Use `port = 0` for WireMock and gRPC Mock (dynamic ports, CI-safe)
- Test through HTTP endpoints; verify DB state and events as side effects
- Use `shouldBePublished<Event>(atLeastIn = 10.seconds) { ... }` — never `Thread.sleep`
- Use `Stove { keepDependenciesRunning() }` locally for faster iteration; disable in CI
- **AI agent feedback loop**: Enable tracing + reporting. When tests fail, the execution report contains the full call chain, system snapshots, and timeline. AI agents can parse this structured output to understand exactly what went wrong inside the application and iterate on fixes with precise feedback.

## Running tests

```bash
./gradlew e2eTest
./gradlew e2eTest --tests "com.myapp.e2e.OrderE2ETest"
```

## Additional resources

- [gradle-config.md](gradle-config.md) — Source set, e2eTest task, IDE integration, artifact list
- [system-setup.md](system-setup.md) — All system configuration options
- [writing-tests.md](writing-tests.md) — Complete test DSL reference with examples
- [tracing.md](tracing.md) — Tracing plugin options and validation DSL
- [custom-systems.md](custom-systems.md) — Writing your own Stove system
