# <span data-rn="underline" data-rn-color="#ff9800">Bridge</span>

The Bridge component gives you <span data-rn="highlight" data-rn-color="#00968855" data-rn-duration="800">direct access to your application's dependency injection (DI) container</span> from your tests. This lets you grab any bean or service your application has registered, which is super useful for testing internal state, verifying side effects, or setting up test data through your application's own services.

## When You'd Use This

When writing end-to-end tests, you often need to:

- **Check internal state** that isn't exposed through APIs
- **Use application services** to set up test data
- **Call domain services directly** to test business logic
- **Swap out time-dependent implementations** for deterministic tests
- **Verify side effects** that happen inside the application

Bridge gives you a type-safe way to access any component from your application's DI container.

## Configuration

Bridge is built into the framework starters, so no extra dependency is needed.

=== "Spring Boot"

    ```kotlin
    dependencies {
        testImplementation("com.trendyol:stove-spring:$version")
    }
    ```

=== "Ktor"

    ```kotlin
    dependencies {
        testImplementation("com.trendyol:stove-ktor:$version")
    }
    ```

### Setup

Enable Bridge in your Stove configuration:

```kotlin hl_lines="5 7"
Stove()
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

```kotlin hl_lines="2"
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

Ktor Bridge supports multiple dependency injection frameworks with automatic detection:

- **Koin** - Popular DI framework for Kotlin
- **Ktor-DI** - Ktor's native DI plugin
- **Custom** - Any DI framework via custom resolver

```kotlin
// Bridge resolves beans from your DI container
using<UserRepository> {
    // 'this' is the UserRepository from your DI
    save(user)
}
```

#### DI Framework Setup

**Using Koin:**

```kotlin
dependencies {
    testImplementation("io.insert-koin:koin-ktor:$koinVersion")
}

// In your test setup - bridge() auto-detects Koin
Stove()
    .with {
        bridge()
        ktor(runner = { params -> MyApp.run(params) })
    }
    .run()
```

**Using Ktor-DI:**

```kotlin
dependencies {
    testImplementation("io.ktor:ktor-server-di:$ktorVersion")
}

// In your test setup - bridge() auto-detects Ktor-DI
Stove()
    .with {
        bridge()
        ktor(runner = { params -> MyApp.run(params) })
    }
    .run()
```

**Using Custom Resolver:**

```kotlin
// For any other DI framework (Kodein, Dagger, etc.)
Stove()
    .with {
        bridge { application, type ->
            // type is KType - preserves generic info like List<T>
            myDiContainer.resolve(type)
        }
        ktor(runner = { params -> MyApp.run(params) })
    }
    .run()
```

#### Generic Type Resolution

Bridge preserves generic type information, enabling resolution of types like `List<Service>`:

```kotlin
// Works with Koin or Ktor-DI
using<List<PaymentService>> {
    forEach { service -> service.pay(order) }
}
```

#### Registering Test Dependencies in Ktor

Unlike Spring Boot's unified `addTestDependencies`, Ktor test dependency registration differs by DI framework:

**Koin - Using Modules:**

```kotlin
object MyApp {
    fun run(
        args: Array<String>,
        testModules: List<Module> = emptyList()  // Accept test modules
    ): Application {
        return embeddedServer(Netty, port = args.getPort()) {
            install(Koin) {
                modules(
                    productionModule,
                    *testModules.toTypedArray()  // Add test modules
                )
            }
            configureRouting()
        }.start(wait = false).application
    }
}

// In your test setup
Stove()
    .with {
        bridge()
        ktor(
            runner = { params ->
                MyApp.run(
                    params,
                    testModules = listOf(
                        module {
                            // Override production beans with test doubles
                            single<TimeProvider>(override = true) { FixedTimeProvider() }
                            single<EmailService>(override = true) { MockEmailService() }
                        }
                    )
                )
            }
        )
    }
    .run()
```

**Ktor-DI - Using Dependencies Block:**

```kotlin
object MyApp {
    fun run(
        args: Array<String>,
        testDependencies: (DependencyRegistrar.() -> Unit)? = null  // Accept test registrations
    ): Application {
        return embeddedServer(Netty, port = args.getPort()) {
            install(DI) {
                dependencies {
                    // Production dependencies
                    provide<UserService> { UserServiceImpl() }
                    provide<TimeProvider> { SystemTimeProvider() }
                    
                    // Apply test overrides if provided
                    testDependencies?.invoke(this)
                }
            }
            configureRouting()
        }.start(wait = false).application
    }
}

// In your test setup
Stove()
    .with {
        bridge()
        ktor(
            runner = { params ->
                MyApp.run(params) {
                    // Override production beans with test doubles
                    provide<TimeProvider> { FixedTimeProvider() }
                    provide<EmailService> { MockEmailService() }
                }
            }
        )
    }
    .run()
```

!!! tip "Test Dependency Patterns"
    - **Koin**: Use `override = true` in test modules to replace production beans
    - **Ktor-DI**: Later `provide<T>` calls override earlier ones
    - Both frameworks support the pattern of passing test-specific configuration to your app's run function

## Usage

### Single Bean Access

Access a single bean and perform operations:

```kotlin
stove {
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
stove {
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
stove {
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
    stove {
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
    stove {
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
    stove {
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
    stove {
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

```kotlin hl_lines="9 18 34"
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

// Register test implementation in your Stove setup
addTestDependencies {
    bean<TimeProvider>(isPrimary = true) { FixedTimeProvider(Instant.parse("2024-01-01T00:00:00Z")) }
}

// Use in tests
test("should expire session after timeout") {
    stove {
        // Create session and capture the session ID
        var sessionId: String = ""
        http {
            postAndExpectBody<SessionResponse>("/login", body = credentials.some()) { response ->
                sessionId = response.body().sessionId
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
// Test event listener (registered via addTestDependencies)
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
    stove {
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

## Test Bean Registration

Register test-specific beans using `addTestDependencies`:

**Spring Boot 2.x / 3.x:**

```kotlin
import com.trendyol.stove.addTestDependencies

Stove()
    .with {
        bridge()
        springBoot(
            runner = { params -> 
                runApplication<MyApp>(*params) {
                    addTestDependencies {
                        // Replace production beans with test doubles
                        bean<TimeProvider>(isPrimary = true) { FixedTimeProvider(Instant.now()) }
                        bean<EmailService>(isPrimary = true) { MockEmailService() }
                        
                        // Add test utilities
                        bean<TestEventCapture>()
                        bean<TestDataBuilder>()
                    }
                }
            }
        )
    }
    .run()
```

**Spring Boot 4.x:**

```kotlin
import com.trendyol.stove.addTestDependencies4x

Stove()
    .with {
        bridge()
        springBoot(
            runner = { params -> 
                runApplication<MyApp>(*params) {
                    addTestDependencies4x {
                        // Replace production beans with test doubles
                        registerBean<TimeProvider>(primary = true) { FixedTimeProvider(Instant.now()) }
                        registerBean<EmailService>(primary = true) { MockEmailService() }
                        
                        // Add test utilities
                        registerBean<TestEventCapture>()
                        registerBean<TestDataBuilder>()
                    }
                }
            }
        )
    }
    .run()
```

### Alternative: Using `addInitializers` Directly

For more control, you can use `addInitializers` with `stoveSpringRegistrar`:

```kotlin
// Spring Boot 2.x / 3.x
addInitializers(stoveSpringRegistrar {
    bean<TimeProvider>(isPrimary = true) { FixedTimeProvider(Instant.now()) }
    bean<TestEventCapture>()
})

// Spring Boot 4.x
addInitializers(stoveSpring4xRegistrar {
    registerBean<TimeProvider>(primary = true) { FixedTimeProvider(Instant.now()) }
    registerBean<TestEventCapture>()
})
```

## Integration with Other Systems

Bridge works seamlessly with other Stove systems:

```kotlin
test("should process order end-to-end") {
    stove {
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
stove {
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
addTestDependencies {
    bean<Clock>(isPrimary = true) { Clock.fixed(fixedInstant, ZoneId.UTC) }
}

// ❌ Avoid: Replacing too many components (reduces test value)
addTestDependencies {
    bean<UserService>(isPrimary = true) { MockUserService() }
    bean<OrderService>(isPrimary = true) { MockOrderService() }
    bean<PaymentService>(isPrimary = true) { MockPaymentService() }
}
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

<span data-rn="underline" data-rn-color="#009688">Bridge is essential for comprehensive e2e testing</span>, allowing you to verify and control aspects of your application that aren't accessible through external interfaces alone.
