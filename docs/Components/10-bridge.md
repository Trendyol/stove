# Bridge

The Bridge component provides direct access to your application's dependency injection (DI) container from within your tests. This enables you to resolve and use any bean or service registered in your application, making it possible to test internal state, verify side effects, or set up test data through application services.

## Overview

When testing an application end-to-end, you often need to:

- **Verify internal state** that isn't exposed through APIs
- **Access application services** to set up test data
- **Invoke domain services** directly to test business logic
- **Replace time-dependent implementations** for deterministic tests
- **Verify side effects** that happen within the application

The Bridge provides a type-safe way to access any component from your application's DI container.

## Configuration

Bridge is built into the framework starters, so no extra dependency is needed.

=== "Spring Boot"

    ```kotlin
    dependencies {
        testImplementation("com.trendyol:stove-spring-testing-e2e:$version")
    }
    ```

=== "Ktor"

    ```kotlin
    dependencies {
        testImplementation("com.trendyol:stove-ktor-testing-e2e:$version")
    }
    ```

### Setup

Enable Bridge in your TestSystem configuration:

```kotlin
TestSystem()
  .with {
    httpClient { HttpClientSystemOptions(baseUrl = "http://localhost:8080") }
    
    bridge()  // Enable access to DI container
    
    springBoot(
      runner = { params -> myApp.run(params) },
      withParameters = listOf("server.port=8080")
    )
  }
  .run()
```

## Framework Support

### Spring Boot

For Spring Boot applications, Bridge provides access to the `ApplicationContext`:

```kotlin
// Bridge resolves beans from ApplicationContext
using<UserService> {
    // 'this' is the UserService bean from Spring context
    findById(123)
}
```

Under the hood, it uses `ApplicationContext.getBean()`:

```kotlin
class SpringBridgeSystem(testSystem: TestSystem) : BridgeSystem<ApplicationContext>(testSystem) {
    override fun <D : Any> get(klass: KClass<D>): D = ctx.getBean(klass.java)
}
```

### Ktor

For Ktor applications using Koin, Bridge provides access to the Koin container:

```kotlin
// Bridge resolves beans from Koin
using<UserRepository> {
    // 'this' is the UserRepository from Koin
    save(user)
}
```

Under the hood, it uses Koin's `getKoin().get()`:

```kotlin
class KtorBridgeSystem(testSystem: TestSystem) : BridgeSystem<Application>(testSystem) {
    override fun <D : Any> get(klass: KClass<D>): D = ctx.getKoin().get(klass)
}
```

## Usage

### Single Bean Access

Access a single bean and perform operations:

```kotlin
TestSystem.validate {
    using<UserService> {
        // 'this' refers to UserService
        val user = findById(123)
        user.name shouldBe "John Doe"
        user.email shouldBe "john@example.com"
    }
}
```

### Multiple Bean Access

Access multiple beans in a single block (up to 5 beans supported):

```kotlin
TestSystem.validate {
    // Two beans
    using<UserService, OrderService> { userService, orderService ->
        val user = userService.findById(123)
        val orders = orderService.findByUserId(123)
        orders.size shouldBeGreaterThan 0
    }
    
    // Three beans
    using<UserService, ProductService, InventoryService> { users, products, inventory ->
        val product = products.findById("SKU-123")
        val stock = inventory.getStock(product.id)
        stock shouldBeGreaterThan 0
    }
    
    // Four beans
    using<A, B, C, D> { a, b, c, d ->
        // Work with all four services
    }
    
    // Five beans
    using<A, B, C, D, E> { a, b, c, d, e ->
        // Work with all five services
    }
}
```

### Capturing Values for Later Use

When you need to capture a value from inside the `using` block for later use, declare a variable outside the block and assign it inside:

```kotlin
TestSystem.validate {
    // Declare variable outside, assign inside
    var userId: Long = 0
    using<UserService> {
        userId = createUser(CreateUserRequest(name = "John", email = "john@example.com")).id
    }
    
    // Use the captured value in subsequent operations
    http {
        get<UserResponse>("/users/$userId") { user ->
            user.name shouldBe "John"
        }
    }
    
    // Capture multiple values
    var user: User? = null
    var token: String? = null
    using<AuthService> {
        user = register(email = "test@example.com", password = "secret")
        token = generateToken(user!!)
    }
    
    // Or use lateinit for non-nullable types
    lateinit var order: Order
    using<OrderService> {
        order = findById(orderId)
    }
    
    // Use captured values
    http {
        getResponse("/orders/${order.id}", headers = mapOf("Authorization" to "Bearer $token")) { response ->
            response.status shouldBe 200
        }
    }
}
```

!!! tip "Variable Capture Pattern"
    Since `using` blocks don't return values, use the pattern of declaring variables outside and assigning inside when you need to pass data between blocks.

## Use Cases

### 1. Setting Up Test Data

Use application repositories to set up test data:

```kotlin
test("should return user orders") {
    TestSystem.validate {
        // Create test data using application's repository
        var userId: Long = 0
        using<UserRepository> {
            userId = save(User(name = "Test User", email = "test@example.com")).id
        }
        
        using<OrderRepository> {
            save(Order(userId = userId, amount = 100.0))
            save(Order(userId = userId, amount = 250.0))
        }
        
        // Test the API
        http {
            get<List<OrderResponse>>("/users/$userId/orders") { orders ->
                orders.size shouldBe 2
                orders.sumOf { it.amount } shouldBe 350.0
            }
        }
    }
}
```

### 2. Verifying Internal State

Verify state that isn't exposed through APIs:

```kotlin
test("should update inventory after order") {
    TestSystem.validate {
        val productId = "PROD-123"
        
        // Check initial inventory
        var initialStock = 0
        using<InventoryService> {
            initialStock = getStock(productId)
        }
        
        // Place an order via API
        http {
            postAndExpectBodilessResponse(
                uri = "/orders",
                body = CreateOrderRequest(productId = productId, quantity = 5).some()
            ) { response ->
                response.status shouldBe 201
            }
        }
        
        // Verify inventory was reduced (internal side effect)
        using<InventoryService> {
            getStock(productId) shouldBe (initialStock - 5)
        }
    }
}
```

### 3. Testing Domain Services Directly

Test business logic that may be complex to trigger through APIs:

```kotlin
test("should calculate shipping cost correctly") {
    TestSystem.validate {
        using<ShippingCalculator> {
            // Test various scenarios directly
            calculate(weight = 1.0, destination = "US") shouldBe 5.99
            calculate(weight = 5.0, destination = "US") shouldBe 12.99
            calculate(weight = 1.0, destination = "EU") shouldBe 15.99
        }
    }
}
```

### 4. Triggering Scheduled Jobs

Manually trigger scheduled jobs for testing:

```kotlin
test("should process pending orders when scheduler runs") {
    TestSystem.validate {
        // Setup: Create pending orders
        using<OrderRepository> {
            save(Order(status = "PENDING", createdAt = Instant.now().minusHours(2)))
            save(Order(status = "PENDING", createdAt = Instant.now().minusHours(3)))
        }
        
        // Trigger the scheduled job manually
        using<OrderProcessingScheduler> {
            processPendingOrders()
        }
        
        // Verify orders were processed
        using<OrderRepository> {
            findByStatus("PENDING").size shouldBe 0
            findByStatus("PROCESSED").size shouldBe 2
        }
    }
}
```

### 5. Time Control

Control time-dependent behavior:

```kotlin
// First, create a testable time provider interface
interface TimeProvider {
    fun now(): Instant
}

// Production implementation
class SystemTimeProvider : TimeProvider {
    override fun now(): Instant = Instant.now()
}

// Test implementation
class FixedTimeProvider(private var time: Instant) : TimeProvider {
    override fun now(): Instant = time
    fun advance(duration: Duration) { time = time.plus(duration) }
}

// Register test implementation in TestInitializer
class TestInitializer : BaseApplicationContextInitializer({
    bean<TimeProvider>(isPrimary = true) { FixedTimeProvider(Instant.parse("2024-01-01T00:00:00Z")) }
})

// Use in tests
test("should expire session after timeout") {
    TestSystem.validate {
        // Create session
        val sessionId = http {
            postAndExpectBody<SessionResponse>("/login", body = credentials.some()) { 
                it.body().sessionId 
            }
        }
        
        // Advance time past session timeout
        using<FixedTimeProvider> {
            advance(Duration.ofHours(2))
        }
        
        // Session should be expired
        http {
            getResponse("/protected", headers = mapOf("Session-ID" to sessionId)) { response ->
                response.status shouldBe 401
            }
        }
    }
}
```

### 6. Event Verification

Capture and verify domain events:

```kotlin
// Test event listener (registered in TestInitializer)
class TestEventCapture {
    private val events = ConcurrentLinkedQueue<Any>()
    
    @EventListener
    fun capture(event: Any) {
        events.add(event)
    }
    
    inline fun <reified T> getEvents(): List<T> = events.filterIsInstance<T>()
    fun clear() = events.clear()
}

test("should publish UserCreatedEvent when user registers") {
    TestSystem.validate {
        // Clear previous events
        using<TestEventCapture> { clear() }
        
        // Perform action
        http {
            postAndExpectBodilessResponse("/users", body = newUser.some()) { 
                it.status shouldBe 201 
            }
        }
        
        // Verify event was published
        using<TestEventCapture> {
            val events = getEvents<UserCreatedEvent>()
            events.size shouldBe 1
            events.first().email shouldBe newUser.email
        }
    }
}
```

## Test Initializers

Use `BaseApplicationContextInitializer` to register test-specific beans:

```kotlin
class TestInitializer : BaseApplicationContextInitializer({
    // Replace production beans with test doubles
    bean<TimeProvider>(isPrimary = true) { FixedTimeProvider(Instant.now()) }
    bean<EmailService>(isPrimary = true) { MockEmailService() }
    
    // Add test utilities
    bean<TestEventCapture>()
    bean<TestDataBuilder>()
})

fun SpringApplication.addTestDependencies() {
    addInitializers(TestInitializer())
}

// In TestSystem configuration
TestSystem()
    .with {
        bridge()
        springBoot(
            runner = { params -> 
                myApp.run(params) { addTestDependencies() }
            }
        )
    }
    .run()
```

### Extending Initializers

You can extend initializers with additional registrations:

```kotlin
class TestInitializer : BaseApplicationContextInitializer({
    bean<MockEmailService>(isPrimary = true)
}) {
    init {
        // Add more registrations
        register {
            bean<TestEventCapture>()
            bean<FixedClock>(isPrimary = true) { FixedClock(Instant.now()) }
        }
    }
    
    // React to application events
    override fun onEvent(event: ApplicationEvent) {
        when (event) {
            is ContextRefreshedEvent -> println("Context refreshed")
        }
    }
    
    // Execute when application is ready
    override fun applicationReady(applicationContext: GenericApplicationContext) {
        println("Application is ready for testing")
    }
}
```

## Integration with Other Systems

Bridge works seamlessly with other Stove systems:

```kotlin
test("should process order end-to-end") {
    TestSystem.validate {
        val orderId = UUID.randomUUID().toString()
        
        // Mock external payment service
        wiremock {
            mockPost("/payments/charge", statusCode = 200, responseBody = PaymentResult(success = true).some())
        }
        
        // Create order via API
        http {
            postAndExpectBody<OrderResponse>(
                uri = "/orders",
                body = CreateOrderRequest(id = orderId, amount = 99.99).some()
            ) { response ->
                response.status shouldBe 201
            }
        }
        
        // Verify in database using application's repository
        using<OrderRepository> {
            val order = findById(orderId)
            order.status shouldBe "PAID"
            order.paymentId shouldNotBe null
        }
        
        // Verify Kafka event
        kafka {
            shouldBePublished<OrderPaidEvent>(atLeastIn = 10.seconds) {
                actual.orderId == orderId
            }
        }
        
        // Verify in Couchbase (if using)
        couchbase {
            shouldGet<Order>("orders", orderId) { order ->
                order.status shouldBe "PAID"
            }
        }
        
        // Access domain service for additional verification
        using<OrderAnalytics> {
            getTodaysTotalRevenue() shouldBeGreaterThanOrEqual 99.99
        }
  }
}
```

## Best Practices

### 1. Use Bridge for Setup, HTTP for Actions

```kotlin
// ✅ Good: Use bridge for setup, HTTP for testing
using<ProductRepository> {
    save(Product(id = "123", name = "Test", price = 99.99))
}
http {
    get<ProductResponse>("/products/123") { product ->
        product.name shouldBe "Test"
    }
}

// ❌ Avoid: Using bridge for everything
using<ProductService> {
    create(product)
    val retrieved = findById("123")  // Not testing actual API
    retrieved.name shouldBe "Test"
}
```

### 2. Prefer Application Services Over Direct Repository Access

```kotlin
// ✅ Good: Use application services that encapsulate business logic
using<OrderService> {
    createOrder(CreateOrderRequest(...))  // Triggers all business logic
}

// ⚠️ Be careful: Direct repository access bypasses business logic
using<OrderRepository> {
    save(Order(...))  // No validation, no events, no side effects
}
```

### 3. Clean Up Test Data

```kotlin
// Use cleanup functions or explicit cleanup in tests
TestSystem.validate {
    var userId: Long = 0
    using<UserRepository> {
        userId = save(user).id
    }
    
    try {
        // Test logic
        http { /* ... */ }
    } finally {
        // Cleanup
        using<UserRepository> {
            deleteById(userId)
        }
    }
}
```

### 4. Keep Test Beans Minimal

Only replace what's necessary:

```kotlin
// ✅ Good: Replace only time-sensitive components
class TestInitializer : BaseApplicationContextInitializer({
    bean<Clock>(isPrimary = true) { Clock.fixed(fixedInstant, ZoneId.UTC) }
})

// ❌ Avoid: Replacing too many components (reduces test value)
class TestInitializer : BaseApplicationContextInitializer({
    bean<UserService>(isPrimary = true) { MockUserService() }
    bean<OrderService>(isPrimary = true) { MockOrderService() }
    bean<PaymentService>(isPrimary = true) { MockPaymentService() }
})
```

## Summary

The Bridge component enables:

| Capability | Example Use Case |
|------------|-----------------|
| **Bean Access** | Resolve any bean from DI container |
| **State Verification** | Check internal state not exposed by APIs |
| **Test Setup** | Create test data using application services |
| **Time Control** | Replace time providers for deterministic tests |
| **Event Capture** | Verify domain events were published |
| **Job Triggering** | Manually trigger scheduled tasks |
| **Service Testing** | Test domain services directly |

Bridge is essential for comprehensive e2e testing, allowing you to verify and control aspects of your application that aren't accessible through external interfaces alone.
