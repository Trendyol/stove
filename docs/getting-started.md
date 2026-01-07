# Getting Started

Get Stove running in your project in just a few minutes. Stove helps you write end-to-end tests by spinning up your application and all its dependencies (databases, message queues, etc.) together, so you can test the real thing instead of mocks.

## What You'll Need

Make sure you have these installed:

- **JDK 17+** - Stove needs Java 17 or higher
- **Docker** - Get the latest version (Stove uses testcontainers, so Docker is required)
- **Kotlin 1.8+** - For writing your tests
- **Gradle or Maven** - We use Gradle in all examples, but Maven works too

!!! tip "IDE Setup"
    If you're using IntelliJ IDEA, grab the Kotest plugin. It adds run buttons and makes test discovery much smoother.

## Step 1: Add Dependencies

Add Stove to your `build.gradle.kts`:

```kotlin
repositories {
    mavenCentral()
}

dependencies {
    // Import BOM for version management
    testImplementation(platform("com.trendyol:stove-bom:$stoveVersion"))
    
    // Core framework
    testImplementation("com.trendyol:stove")
    
    // Optional: Test framework extension for better failure reporting
    // Choose the one that matches your test framework
    testImplementation("com.trendyol:stove-extensions-kotest")  // For Kotest
    // OR
    testImplementation("com.trendyol:stove-extensions-junit")   // For JUnit 5/6
    
    // Choose your application framework
    testImplementation("com.trendyol:stove-spring")
    // OR
    testImplementation("com.trendyol:stove-ktor")
    // For Ktor, also add your preferred DI framework:
    testImplementation("io.insert-koin:koin-ktor:$koinVersion")  // Koin
    // OR testImplementation("io.ktor:ktor-server-di:$ktorVersion")  // Ktor-DI
    
    // Add components you need
    testImplementation("com.trendyol:stove-http")
    testImplementation("com.trendyol:stove-kafka")
    // ... add more as needed
}
```

!!! info "Latest Version"
    Check the [Releases](https://github.com/Trendyol/stove/releases) page for the latest version.

## Step 2: Prepare Your Application

Stove needs to start your application from tests, which means we need to tweak your main function slightly. Instead of calling `runApplication` or `embeddedServer` directly, we'll extract that logic into a separate `run` function that Stove can call with test-specific parameters.

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

=== "Ktor with Koin"

    ```kotlin
    // Before
    fun main() {
        embeddedServer(Netty, port = 8080) {
            install(Koin) { modules(appModule) }
            configureRouting()
        }.start(wait = true)
    }
    
    // After - Accept test modules for overriding beans
    object MyApp {
        @JvmStatic
        fun main(args: Array<String>) = run(args)
        
        fun run(
            args: Array<String>,
            wait: Boolean = true,
            testModules: List<Module> = emptyList()
        ): Application {
            return embeddedServer(Netty, port = args.getPort()) {
                install(Koin) {
                    modules(appModule, *testModules.toTypedArray())
                }
                configureRouting()
            }.start(wait = wait).application
        }
    }
    ```

=== "Ktor with Ktor-DI"

    ```kotlin
    // Before
    fun main() {
        embeddedServer(Netty, port = 8080) {
            install(DI) { dependencies { provide<MyService> { MyServiceImpl() } } }
            configureRouting()
        }.start(wait = true)
    }
    
    // After - Accept test dependency overrides
    object MyApp {
        @JvmStatic
        fun main(args: Array<String>) = run(args)
        
        fun run(
            args: Array<String>,
            wait: Boolean = true,
            testDependencies: (DependencyRegistrar.() -> Unit)? = null
        ): Application {
            return embeddedServer(Netty, port = args.getPort()) {
                install(DI) {
                    dependencies {
                        provide<MyService> { MyServiceImpl() }
                        testDependencies?.invoke(this)  // Apply test overrides
                    }
                }
                configureRouting()
            }.start(wait = wait).application
        }
    }
    ```

## Step 3: Create Test Configuration

Set up Stove once for your entire test suite. This configuration runs before all your tests and shuts down after they're done. 

We recommend putting e2e tests in a separate `src/test-e2e` source set to keep them separate from unit tests (see [Best Practices](best-practices.md#use-dedicated-source-set-for-e2e-tests) for the Gradle setup).

=== "Kotest"

    ```kotlin
    // src/test-e2e/kotlin/e2e/TestConfig.kt
    import com.trendyol.stove.extensions.kotest.StoveKotestExtension
    import com.trendyol.stove.system.Stove
    import com.trendyol.stove.system.stove
    import com.trendyol.stove.http.*
    import com.trendyol.stove.spring.springBoot
    
    class TestConfig : AbstractProjectConfig() {
        // Optional: Add this for detailed failure reports with execution context
        override val extensions: List<Extension> = listOf(StoveKotestExtension())
        
        override suspend fun beforeProject() {
            Stove()
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
            Stove.stop()
        }
    }
    ```

=== "JUnit"

    ```kotlin
    // src/test-e2e/kotlin/e2e/TestConfig.kt
    import com.trendyol.stove.extensions.junit.StoveJUnitExtension
    import com.trendyol.stove.system.Stove
    import com.trendyol.stove.http.*
    import com.trendyol.stove.spring.springBoot
    import org.junit.jupiter.api.extension.ExtendWith
    
    // Optional: Add this annotation for detailed failure reports
    @ExtendWith(StoveJUnitExtension::class)
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    abstract class BaseE2ETest {
        
        companion object {
            @JvmStatic
            @BeforeAll
            fun setup() = runBlocking {
                Stove()
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
                Stove.stop()
            }
        }
    }
    ```

## Step 4: Write Your First Test

=== "Kotest"

    ```kotlin
    import com.trendyol.stove.system.stove
    
    class MyFirstE2ETest : FunSpec({
        
        test("should return hello world") {
            stove {
                http {
                    get<String>("/hello") { response ->
                        response shouldBe "Hello, World!"
                    }
                }
            }
        }
        
        test("should create a user") {
            stove {
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
    import com.trendyol.stove.system.stove
    
    class MyFirstE2ETest : BaseE2ETest() {
        
        @Test
        fun `should return hello world`() = runBlocking {
            stove {
                http {
                    get<String>("/hello") { response ->
                        response shouldBe "Hello, World!"
                    }
                }
            }
        }
        
        @Test
        fun `should create a user`() = runBlocking {
            stove {
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

Once you've got the basics working, you'll probably want to add more components. Here's how you'd set up a typical stack:

```kotlin
Stove()
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

## Step 6: Write Tests That Span Multiple Systems

Here's where Stove really shines. You can write tests that touch multiple systems and verify everything works together:

```kotlin
import com.trendyol.stove.system.stove

test("should create order and publish event") {
    stove {
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

Run all your tests:

```bash
./gradlew test
```

Or run a specific test class:

```bash
./gradlew test --tests "com.myapp.e2e.OrderE2ETest"
```

If you're using the `test-e2e` source set, you might have a separate task:

```bash
./gradlew e2eTest
```

## Next Steps

Now that you're up and running, here's what to explore next:

- **Components** - Check out the [Components documentation](Components/index.md) to see what's available
- **Reporting** - Set up [Reporting](Components/13-reporting.md) to get detailed failure diagnostics (makes debugging way easier)
- **Best Practices** - Read the [Best Practices guide](best-practices.md) for tips on writing effective e2e tests
- **Troubleshooting** - Hit an issue? Check the [Troubleshooting guide](troubleshooting.md)
- **Examples** - Browse the [Examples](https://github.com/Trendyol/stove/tree/main/examples) to see complete working projects

## Common Patterns

### Keep Containers Running Between Test Runs

Starting containers takes time. During development, you can keep them running between test runs to speed things up:

```kotlin
Stove {
    keepDependenciesRunning()
}.with {
    // Your configuration
}.run()
```

### Using a Custom Container Registry

If you're behind a corporate firewall or need to use a private registry:

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

### Use Unique Test Data

To avoid test conflicts, generate unique data for each test run:

```kotlin
test("should create user") {
    val userId = UUID.randomUUID().toString()
    val email = "test-${UUID.randomUUID()}@example.com"
    
    stove {
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
