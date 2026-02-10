# Best Practices

Here are some practices we've found helpful when writing end-to-end tests with Stove. These aren't hard rules, but they'll make your tests more maintainable and easier to work with.

## Test Organization

### Use Dedicated Source Set for E2E Tests

Instead of placing <span data-rn="underline" data-rn-color="#ff9800">e2e</span> tests in the regular `src/test` folder, <span data-rn="underline" data-rn-color="#009688">create a dedicated `src/test-e2e` source set</span>. This provides better separation between unit/integration tests and e2e tests:

```
src/
├── main/kotlin/           # Application code
├── test/kotlin/           # Unit tests
└── test-e2e/kotlin/       # E2E tests with Stove
    ├── config/
    │   └── TestConfig.kt  # Contains Stove setup
    ├── features/
    │   ├── OrderE2ETest.kt
    │   ├── UserE2ETest.kt
    │   └── ProductE2ETest.kt
    └── shared/
        ├── TestData.kt
        └── Assertions.kt
```

### Gradle Configuration

Here's how to set up the `test-e2e` source set in your `build.gradle.kts`:

```kotlin
sourceSets {
    @Suppress("LocalVariableName")
    val `test-e2e` by creating {
        compileClasspath += sourceSets.main.get().output
        runtimeClasspath += sourceSets.main.get().output
    }
    
    val testE2eImplementation by configurations.getting {
        extendsFrom(configurations.testImplementation.get())
    }
    configurations["testE2eRuntimeOnly"].extendsFrom(configurations.runtimeOnly.get())
}

// Register e2e test task
tasks.register<Test>("e2eTest") {
    description = "Runs e2e tests."
    group = "verification"
    testClassesDirs = sourceSets["test-e2e"].output.classesDirs
    classpath = sourceSets["test-e2e"].runtimeClasspath

    useJUnitPlatform()
    reports {
        junitXml.required.set(true)
        html.required.set(true)
    }
}

// Configure IDEA to recognize test-e2e as test sources
idea {
    module {
        testSources.from(sourceSets["test-e2e"].allSource.sourceDirectories)
        testResources.from(sourceSets["test-e2e"].resources.sourceDirectories)
    }
}
```

### Running E2E Tests

```bash
# Run only e2e tests
./gradlew e2eTest

# Run unit tests (doesn't include e2e)
./gradlew test

# Run all tests
./gradlew test e2eTest
```

### Benefits of Separate Source Set

| Benefit | Description |
|---------|-------------|
| **Isolation** | E2E tests run independently from unit tests |
| **CI Flexibility** | Run unit tests quickly, e2e tests separately or in parallel |
| **Resource Management** | Different JVM settings for e2e tests (more memory, longer timeouts) |
| **Clear Boundaries** | Developers know exactly where e2e tests live |

!!! tip "See Examples"
    Check the [recipes](https://github.com/Trendyol/stove/tree/main/recipes) folder for complete working examples with this structure.

### Single Setup, Multiple Tests

<span data-rn="highlight" data-rn-color="#00968855" data-rn-duration="800">Configure Stove once for all tests:</span>

```kotlin hl_lines="4 10 18"
// ✅ Good: Single configuration for all tests
class TestConfig : AbstractProjectConfig() {
    override suspend fun beforeProject() {
        Stove()
            .with { /* configuration */ }
            .run()
    }
    
    override suspend fun afterProject() {
        Stove.stop()
    }
}

// ❌ Bad: Configuration per test class
class MyTest : FunSpec({
    beforeSpec {
        Stove().with { /* */ }.run()  // Don't do this!
    }
})
```

## Test Data Management

### Use Unique Test Data

Generate unique identifiers to prevent test interference:

```kotlin hl_lines="4 5 18"
// ✅ Good: Unique data per test
test("should create order") {
    val orderId = UUID.randomUUID().toString()
    val userId = "user-${UUID.randomUUID()}"
    
    stove {
        http {
            postAndExpectBody<OrderResponse>(
                uri = "/orders",
                body = CreateOrderRequest(id = orderId, userId = userId).some()
            ) { /* assertions */ }
        }
    }
}

// ❌ Bad: Hardcoded IDs that may conflict
test("should create order") {
    val orderId = "order-123"  // May conflict with other tests
    // ...
}
```

### Isolate Shared Infrastructure Resources

When using provided instances (shared infrastructure in CI/CD), use **unique prefixes for all resources** to prevent parallel test runs from interfering with each other:

```kotlin
object TestRunContext {
    val runId: String = System.getenv("CI_JOB_ID") 
        ?: UUID.randomUUID().toString().take(8)
    
    val databaseName = "testdb_$runId"
    val topicPrefix = "test_${runId}_"
    val indexPrefix = "test_${runId}_"
}

// Use unique names in configuration
Stove()
    .with {
        postgresql {
            PostgresqlOptions.provided(
                databaseName = TestRunContext.databaseName,
                // ...
            )
        }
        springBoot(
            withParameters = listOf(
                "kafka.topic.orders=${TestRunContext.topicPrefix}orders",
                "elasticsearch.index.products=${TestRunContext.indexPrefix}products"
            )
        )
    }
```

!!! tip "Detailed Guide"
    See [Provided Instances - Test Isolation](Components/11-provided-instances.md#test-isolation-with-shared-infrastructure) for comprehensive examples for each system.

### Use Cleanup Functions

Clean up test data to maintain isolation. The `cleanup` parameter is passed inside the options:

```kotlin
Stove()
    .with {
        couchbase {
            CouchbaseSystemOptions(
                defaultBucket = "bucket",
                cleanup = { cluster ->
                    // Clean test data after tests complete
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
        
        kafka {
            KafkaSystemOptions(
                cleanup = { admin ->
                    // Delete test topics after tests complete
                    val testTopics = admin.listTopics().names().get()
                        .filter { it.startsWith("test-") }
                    if (testTopics.isNotEmpty()) {
                        admin.deleteTopics(testTopics).all().get()
                    }
                },
                configureExposedConfiguration = { cfg ->
                    listOf(
                        "kafka.bootstrapServers=${cfg.bootstrapServers}",
                        "kafka.interceptorClasses=${cfg.interceptorClass}"
                    )
                }
            )
        }
    }
    .run()
```

### Test Data Builders

Create reusable test data builders:

```kotlin
object TestData {
    fun createUser(
        id: String = UUID.randomUUID().toString(),
        name: String = "Test User",
        email: String = "test-${UUID.randomUUID()}@example.com"
    ) = User(id = id, name = name, email = email)
    
    fun createProduct(
        id: String = UUID.randomUUID().toString(),
        name: String = "Test Product",
        price: Double = 99.99
    ) = Product(id = id, name = name, price = price)
}

// Usage in tests
test("should create user") {
    val user = TestData.createUser(name = "John Doe")
    // ...
}
```

## Assertions

### Be Specific with Assertions

Test specific behaviors, not just successful responses:

```kotlin
// ✅ Good: Specific assertions
stove {
    http {
        postAndExpectBody<OrderResponse>(
            uri = "/orders",
            body = CreateOrderRequest(id = orderId, amount = 99.99).some()
        ) { response ->
            response.status shouldBe 201
            response.body().id shouldBe orderId
            response.body().amount shouldBe 99.99
            response.body().status shouldBe "CREATED"
            response.body().createdAt shouldNotBe null
        }
    }
}

// ❌ Bad: Only checking status code
stove {
    http {
        postAndExpectBodilessResponse("/orders", body = order.some()) { response ->
            response.status shouldBe 201  // Not enough!
        }
    }
}
```

### <span data-rn="underline" data-rn-color="#009688">Verify Side Effects</span>

<span data-rn="underline" data-rn-color="#009688">Test the complete flow including side effects: make the request, then verify database state, published events, search index, and cache.</span>

```kotlin hl_lines="8 17 24 31 38"
test("should process order completely") {
    val orderId = UUID.randomUUID().toString()
    
    stove {
        // 1. Make the request
        http {
            postAndExpectBody<OrderResponse>(
                uri = "/orders",
                body = CreateOrderRequest(id = orderId).some()
            ) { response ->
                response.status shouldBe 201
            }
        }
        
        // 2. Verify database state
        couchbase {
            shouldGet<Order>("orders", orderId) { order ->
                order.status shouldBe "CREATED"
            }
        }
        
        // 3. Verify event was published
        kafka {
            shouldBePublished<OrderCreatedEvent>(atLeastIn = 10.seconds) {
                actual.orderId == orderId
            }
        }
        
        // 4. Verify search index updated
        elasticsearch {
            shouldGet<Order>(index = "orders", key = orderId) { order ->
                order.status shouldBe "CREATED"
            }
        }
        
        // 5. Verify cache populated
        redis {
            client().connect().sync().get("order:$orderId") shouldNotBe null
        }
    }
}
```

## Performance

### Use keepDependenciesRunning for Development

Speed up local development:

```kotlin
Stove {
    keepDependenciesRunning()  // Containers stay running between test runs
}.with {
    // ...
}.run()
```

!!! tip
    Disable `keepDependenciesRunning()` in CI/CD for clean environments.

### Configure Appropriate Timeouts

Set realistic timeouts for your environment:

```kotlin
// HTTP client timeout
http {
    HttpClientSystemOptions(
        baseUrl = "http://localhost:8080",
        timeout = 30.seconds  // Adjust based on your app's response times
    )
}

// Kafka assertion timeout
kafka {
    shouldBePublished<OrderCreatedEvent>(atLeastIn = 20.seconds) {
        // Allow enough time for async processing
        actual.orderId == orderId
    }
}
```

### Run Tests in Parallel (With Care)

If running tests in parallel, ensure proper isolation:

```kotlin
// Use unique data per test
test("test 1") {
    val id = UUID.randomUUID().toString()  // Unique per test
    // ...
}

test("test 2") {
    val id = UUID.randomUUID().toString()  // Different ID
    // ...
}
```

## External Services

### Mock External Dependencies

Use WireMock for external services:

```kotlin
// ✅ Good: Mock external services
stove {
    wiremock {
        mockPost(
            url = "/payments/charge",
            statusCode = 200,
            responseBody = PaymentResult(success = true, transactionId = "tx-123").some()
        )
    }
    
    http {
        postAndExpectBody<OrderResponse>(
            uri = "/orders",
            body = CreateOrderRequest(amount = 99.99).some()
        ) { response ->
            response.body().paymentStatus shouldBe "PAID"
        }
    }
}

// ❌ Bad: Calling real external services in tests
// - Tests become flaky
// - Tests are slow
// - May incur costs
// - Can't test edge cases
```

### Test Error Scenarios

<span data-rn="highlight" data-rn-color="#ff980055" data-rn-duration="800">Test how your application handles failures:</span>

```kotlin
test("should handle payment failure gracefully") {
    stove {
        wiremock {
            mockPost(
                url = "/payments/charge",
                statusCode = 500,
                responseBody = ErrorResponse("Payment service unavailable").some()
            )
        }
        
        http {
            postAndExpectBody<OrderResponse>(
                uri = "/orders",
                body = CreateOrderRequest(amount = 99.99).some()
            ) { response ->
                response.status shouldBe 503
                response.body().status shouldBe "PAYMENT_FAILED"
            }
        }
    }
}

test("should retry on transient failures") {
    stove {
        wiremock {
            behaviourFor("/payments/charge", WireMock::post) {
                initially {
                    aResponse().withStatus(503)
                }
                then {
                    aResponse().withStatus(503)
                }
                then {
                    aResponse()
                        .withStatus(200)
                        .withBody(it.serialize(PaymentResult(success = true)))
                }
            }
        }
        
        // Application should retry and eventually succeed
        http {
            postAndExpectBody<OrderResponse>(
                uri = "/orders",
                body = CreateOrderRequest(amount = 99.99).some()
            ) { response ->
                response.status shouldBe 201
            }
        }
    }
}
```

## Serialization

### Align Serializers

<span data-rn="highlight" data-rn-color="#00968855" data-rn-duration="800">Ensure Stove uses the same serialization as your application:</span>

```kotlin
// If your app uses custom Jackson configuration
val customObjectMapper = ObjectMapper().apply {
    registerModule(JavaTimeModule())
    disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
    setSerializationInclusion(JsonInclude.Include.NON_NULL)
}

Stove()
    .with {
        http {
            HttpClientSystemOptions(
                baseUrl = "http://localhost:8080",
                contentConverter = JacksonConverter(customObjectMapper)
            )
        }
        
        kafka {
            KafkaSystemOptions(
                serde = StoveSerde.jackson.anyByteArraySerde(customObjectMapper)
            ) { /* config */ }
        }
        
        wiremock {
            WireMockSystemOptions(
                serde = StoveSerde.jackson.anyByteArraySerde(customObjectMapper)
            )
        }
    }
    .run()
```

## Application Configuration

### Make Configuration Testable

Your application should accept configuration from various sources:

```kotlin
// ✅ Good: Configurable properties
@Configuration
class KafkaConfig(
    @Value("\${kafka.bootstrapServers}") private val bootstrapServers: String,
    @Value("\${kafka.offset:latest}") private val offset: String,
    @Value("\${kafka.autoCreateTopics:false}") private val autoCreate: Boolean
) {
    // Stove can override these via command line args
}
```

### External Service URLs Must Be Configurable

When using WireMock, <span data-rn="box" data-rn-color="#ef5350">all external service URLs must point to WireMock's URL</span>:

```kotlin hl_lines="4 5 16 17"
// ✅ Good: External service URLs are configurable
@Configuration
class ExternalServicesConfig(
    @Value("\${payment.service.url}") val paymentUrl: String,
    @Value("\${inventory.service.url}") val inventoryUrl: String
)

// In tests, pass WireMock URL for all external services
Stove()
    .with {
        wiremock {
            WireMockSystemOptions(port = 9090)
        }
        springBoot(
            withParameters = listOf(
                "payment.service.url=http://localhost:9090",
                "inventory.service.url=http://localhost:9090"
            )
        )
    }
```

```kotlin hl_lines="3"
// ❌ Bad: Hardcoded URLs won't be intercepted by WireMock
class PaymentClient {
    private val url = "http://payment-service.com"  // WireMock can't intercept this!
}
```

```kotlin hl_lines="4"
// ❌ Bad: Hardcoded values
@Configuration
class KafkaConfig {
    private val bootstrapServers = "localhost:9092"  // Can't change in tests!
}
```

### Use Test Profiles Wisely

<span data-rn="underline" data-rn-color="#009688">Minimize differences between test and production:</span>

```kotlin
springBoot(
    runner = { params -> myApp.run(params) },
    withParameters = listOf(
        "server.port=8080",
        "spring.profiles.active=default",  // Use default profile when possible
        "logging.level.root=warn",
        // Override only what's necessary
        "kafka.bootstrapServers=${kafkaConfig.bootstrapServers}"
    )
)
```

## Debugging

### Enable Verbose Logging When Needed

```kotlin
springBoot(
    runner = { params -> myApp.run(params) },
    withParameters = listOf(
        "logging.level.root=debug",  // For debugging
        "logging.level.org.springframework.web=trace"
    )
)
```

### Use Container Inspection

Debug container issues:

```kotlin
stove {
    mongodb {
        val info = inspect()
        println("Container ID: ${info?.containerId}")
        println("Network: ${info?.network}")
        println("IP: ${info?.ipAddress}")
    }
}
```

### Access Application Beans

Debug by accessing application components:

```kotlin
stove {
    using<OrderRepository> {
        val order = findById(orderId)
        println("Order state: $order")
    }
    
    using<OrderService, PaymentService> { orderService, paymentService ->
        // Debug complex scenarios
    }
}
```

## CI/CD Considerations

### Use Provided Instances in CI

For faster CI builds, use pre-provisioned infrastructure:

```kotlin
val isCI = System.getenv("CI") == "true"

Stove()
    .with {
        kafka {
            if (isCI) {
                KafkaSystemOptions.provided(
                    bootstrapServers = System.getenv("KAFKA_SERVERS"),
                    configureExposedConfiguration = { cfg ->
                        listOf("kafka.bootstrapServers=${cfg.bootstrapServers}")
                    }
                )
            } else {
                KafkaSystemOptions {
                    listOf("kafka.bootstrapServers=${it.bootstrapServers}")
                }
            }
        }
    }
    .run()
```

### Configure Docker Registry

For corporate environments:

```kotlin
// Set globally for all components
DEFAULT_REGISTRY = System.getenv("DOCKER_REGISTRY") ?: "docker.io"
```

### Handle Resource Constraints

Configure for CI resource limits:

```kotlin
Stove()
    .with {
        couchbase {
            CouchbaseSystemOptions(
                container = CouchbaseContainerOptions(
                    containerFn = { container ->
                        container.withCreateContainerCmdModifier { cmd ->
                            cmd.hostConfig?.withMemory(512 * 1024 * 1024)  // 512MB limit
                        }
                    }
                )
            ) { /* config */ }
        }
    }
    .run()
```

## Common Anti-Patterns

### ❌ Testing Implementation Details

```kotlin
// Bad: Testing internal implementation
using<OrderRepository> {
    save(order)
}
shouldGet<Order>(orderId) { /* verify */ }

// Good: Test through the API
http {
    postAndExpectBody<OrderResponse>("/orders", body = order.some()) { /* verify */ }
}
couchbase {
    shouldGet<Order>("orders", orderId) { /* verify */ }
}
```

### ❌ Sleeping Instead of Waiting

```kotlin hl_lines="4 9"
// Bad: Fixed sleep
http { post("/async-operation") }
Thread.sleep(5000)  // Fragile!
kafka { shouldBeConsumed<Event> { true } }

// Good: Poll with timeout
kafka {
    shouldBePublished<Event>(atLeastIn = 10.seconds) {
        actual.id == expectedId
    }
}
```

### ❌ Sharing State Between Tests

```kotlin hl_lines="2 5 9 14"
// Bad: Shared mutable state
var createdUserId: String? = null

test("create user") {
    createdUserId = createUser()
}

test("get user") {
    getUser(createdUserId!!)  // Depends on test order!
}

// Good: Independent tests
test("create and get user") {
    val userId = createUser()
    getUser(userId)
}
```

### ❌ Overly Broad Assertions

```kotlin
// Bad: Too vague
response.status shouldBe 200

// Good: Specific assertions
response.status shouldBe 200
response.body().id shouldBe expectedId
response.body().status shouldBe "ACTIVE"
response.body().createdAt shouldNotBe null
```

## Summary

| Do | Don't |
|----|-------|
| Use unique test data | Use hardcoded IDs |
| Test through public APIs | Test implementation details |
| Mock external services | Call real external services |
| Use appropriate timeouts | Use fixed sleeps |
| Clean up test data | Leave test artifacts |
| Keep tests independent | Share state between tests |
| Be specific in assertions | Use vague assertions |
| Test error scenarios | Only test happy paths |
