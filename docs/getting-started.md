# Getting Started

This guide will help you get Stove up and running in your project in just a few minutes.

## Prerequisites

Before you begin, ensure you have:

- **JDK 17+** - Stove requires Java 17 or higher
- **Docker** - Latest version recommended (Stove uses testcontainers under the hood)
- **Kotlin 1.8+** - For writing your tests
- **Gradle or Maven** - Gradle is recommended and used in all examples

!!! tip "IDE Setup"
    If you're using IntelliJ IDEA, install the Kotest plugin for a better testing experience with run buttons and test discovery.

## Step 1: Add Dependencies

Add Stove to your `build.gradle.kts`:

```kotlin
repositories {
    mavenCentral()
}

dependencies {
    // Core framework
    testImplementation("com.trendyol:stove-testing-e2e:$stoveVersion")
    
    // Choose your application framework
    testImplementation("com.trendyol:stove-spring-testing-e2e:$stoveVersion")
    // OR
    testImplementation("com.trendyol:stove-ktor-testing-e2e:$stoveVersion")
    
    // Add components you need
    testImplementation("com.trendyol:stove-testing-e2e-http:$stoveVersion")
    testImplementation("com.trendyol:stove-testing-e2e-kafka:$stoveVersion")
    // ... add more as needed
}
```

!!! info "Latest Version"
    Check the [Releases](https://github.com/Trendyol/stove/releases) page for the latest version.

## Step 2: Prepare Your Application

Stove needs to start your application from the test context. This requires a small modification to your main function.

=== "Spring Boot"

    ```kotlin
    // Before
    @SpringBootApplication
    class MyApplication
    
    fun main(args: Array<String>) {
        runApplication<MyApplication>(*args)
    }
    
    // After
    @SpringBootApplication
    class MyApplication
    
    fun main(args: Array<String>) = run(args)
    
    fun run(
        args: Array<String>,
        init: SpringApplication.() -> Unit = {}
    ): ConfigurableApplicationContext {
        return runApplication<MyApplication>(*args, init = init)
    }
    ```

=== "Ktor"

    ```kotlin
    // Before
    fun main() {
        embeddedServer(Netty, port = 8080) {
            configureRouting()
        }.start(wait = true)
    }
    
    // After
    object MyApp {
        @JvmStatic
        fun main(args: Array<String>) = run(args)
        
        fun run(
            args: Array<String>,
            wait: Boolean = true,
            configure: Application.() -> Unit = {}
        ): Application {
            // Your application setup
            return embeddedServer(Netty, port = args.getPort()) {
                configureRouting()
                configure()
            }.start(wait = wait)
        }
    }
    ```

## Step 3: Create Test Configuration

Set up Stove once for your entire test suite. We recommend using a dedicated `src/test-e2e` source set for e2e tests (see [Best Practices](best-practices.md#use-dedicated-source-set-for-e2e-tests) for Gradle configuration).

=== "Kotest"

    ```kotlin
    // src/test-e2e/kotlin/e2e/TestConfig.kt
    class TestConfig : AbstractProjectConfig() {
        
        override suspend fun beforeProject() {
            TestSystem()
                .with {
                    httpClient {
                        HttpClientSystemOptions(
                            baseUrl = "http://localhost:8080"
                        )
                    }
                    
                    springBoot(
                        runner = { params -> 
                            com.myapp.run(params)
                        },
                        withParameters = listOf(
                            "server.port=8080",
                            "logging.level.root=warn"
                        )
                    )
                }
                .run()
        }
        
        override suspend fun afterProject() {
            TestSystem.stop()
        }
    }
    ```

=== "JUnit"

    ```kotlin
    // src/test-e2e/kotlin/e2e/TestConfig.kt
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    abstract class BaseE2ETest {
        
        companion object {
            @JvmStatic
            @BeforeAll
            fun setup() = runBlocking {
                TestSystem()
                    .with {
                        httpClient {
                            HttpClientSystemOptions(
                                baseUrl = "http://localhost:8080"
                            )
                        }
                        
                        springBoot(
                            runner = { params -> 
                                com.myapp.run(params)
                            },
                            withParameters = listOf(
                                "server.port=8080",
                                "logging.level.root=warn"
                            )
                        )
                    }
                    .run()
            }
            
            @JvmStatic
            @AfterAll
            fun teardown() = runBlocking {
                TestSystem.stop()
            }
        }
    }
    ```

## Step 4: Write Your First Test

=== "Kotest"

    ```kotlin
    class MyFirstE2ETest : FunSpec({
        
        test("should return hello world") {
            TestSystem.validate {
                http {
                    get<String>("/hello") { response ->
                        response shouldBe "Hello, World!"
                    }
                }
            }
        }
        
        test("should create a user") {
            TestSystem.validate {
                http {
                    postAndExpectBody<UserResponse>(
                        uri = "/users",
                        body = CreateUserRequest(name = "John", email = "john@example.com").some()
                    ) { response ->
                        response.status shouldBe 201
                        response.body().name shouldBe "John"
                    }
                }
            }
        }
    })
    ```

=== "JUnit"

    ```kotlin
    class MyFirstE2ETest : BaseE2ETest() {
        
        @Test
        fun `should return hello world`() = runBlocking {
            TestSystem.validate {
                http {
                    get<String>("/hello") { response ->
                        response shouldBe "Hello, World!"
                    }
                }
            }
        }
        
        @Test
        fun `should create a user`() = runBlocking {
            TestSystem.validate {
                http {
                    postAndExpectBody<UserResponse>(
                        uri = "/users",
                        body = CreateUserRequest(name = "John", email = "john@example.com").some()
                    ) { response ->
                        response.status shouldBe 201
                        response.body().name shouldBe "John"
                    }
                }
            }
        }
    }
    ```

## Step 5: Add More Components

As your application grows, add more components:

```kotlin
TestSystem()
    .with {
        httpClient {
            HttpClientSystemOptions(baseUrl = "http://localhost:8080")
        }
        
        // Add Kafka for event-driven tests
        kafka {
            KafkaSystemOptions {
                listOf(
                    "kafka.bootstrapServers=${it.bootstrapServers}",
                    "kafka.interceptorClasses=${it.interceptorClass}"
                )
            }
        }
        
        // Add Couchbase for database tests
        couchbase {
            CouchbaseSystemOptions(
                defaultBucket = "myBucket",
                configureExposedConfiguration = { cfg ->
                    listOf(
                        "couchbase.hosts=${cfg.hostsWithPort}",
                        "couchbase.username=${cfg.username}",
                        "couchbase.password=${cfg.password}"
                    )
                }
            )
        }
        
        // Add WireMock for external service mocking
        wiremock {
            WireMockSystemOptions(port = 9090)
        }
        
        // Add bridge for DI container access
        bridge()
        
        springBoot(
            runner = { params -> com.myapp.run(params) },
            withParameters = listOf(
                "server.port=8080",
                "external.service.url=http://localhost:9090"
            )
        )
    }
    .run()
```

## Step 6: Write Comprehensive Tests

Now you can write tests that span multiple systems:

```kotlin
test("should create order and publish event") {
    TestSystem.validate {
        val orderId = UUID.randomUUID().toString()
        
        // Mock external payment service
        wiremock {
            mockPost(
                url = "/payments",
                statusCode = 200,
                responseBody = PaymentResult(success = true).some()
            )
        }
        
        // Create order via API
        http {
            postAndExpectBody<OrderResponse>(
                uri = "/orders",
                body = CreateOrderRequest(
                    id = orderId,
                    items = listOf("item1", "item2"),
                    amount = 99.99
                ).some()
            ) { response ->
                response.status shouldBe 201
            }
        }
        
        // Verify order stored in database
        couchbase {
            shouldGet<Order>("orders", orderId) { order ->
                order.status shouldBe "CREATED"
                order.amount shouldBe 99.99
            }
        }
        
        // Verify event was published
        kafka {
            shouldBePublished<OrderCreatedEvent>(atLeastIn = 10.seconds) {
                actual.orderId == orderId &&
                actual.amount == 99.99
            }
        }
        
        // Access application beans directly
        using<OrderService> {
            val order = getOrder(orderId)
            order.status shouldBe "CREATED"
        }
    }
}
```

## Running Tests

Run your tests using Gradle:

```bash
./gradlew test
```

Or run specific test classes:

```bash
./gradlew test --tests "com.myapp.e2e.OrderE2ETest"
```

## Next Steps

- Explore [Components](Components/index.md) documentation for each available component
- Learn about [Best Practices](best-practices.md) for writing effective e2e tests
- Check [Troubleshooting](troubleshooting.md) if you encounter issues
- Browse [Examples](https://github.com/Trendyol/stove/tree/main/examples) for complete working projects

## Common Patterns

### Keep Dependencies Running

For faster development cycles, keep containers running between test runs:

```kotlin
TestSystem {
    keepDependenciesRunning()
}.with {
    // Your configuration
}.run()
```

### Custom Container Registry

If you're behind a corporate firewall:

```kotlin
// Set globally
DEFAULT_REGISTRY = "your.registry.com"

// Or per component
kafka {
    KafkaSystemOptions(
        container = KafkaContainerOptions(
            registry = "your.registry.com"
        )
    )
}
```

### Use Random Test Data

Generate unique data for each test:

```kotlin
test("should create user") {
    val userId = UUID.randomUUID().toString()
    val email = "test-${UUID.randomUUID()}@example.com"
    
    TestSystem.validate {
        // Use unique data to avoid conflicts
    }
}
```

## Troubleshooting Quick Tips

| Problem | Solution |
|---------|----------|
| Docker not found | Ensure Docker is running and accessible |
| Port conflicts | Use dynamic ports or ensure no conflicts |
| Slow startup | Enable `keepDependenciesRunning()` for development |
| Serialization errors | Configure `StoveSerde` to match your app's serializer |
| Test isolation issues | Use unique test data and cleanup functions |

For more help, see the [Troubleshooting Guide](troubleshooting.md).
