# Writing Custom Systems

One of Stove's most powerful features is its extensibility. You can create your own custom systems to integrate with any component or capture any behavior specific to your application.

## Why Write Custom Systems?

Custom systems are useful when you need to:

- **Capture application events** in memory for testing
- **Integrate with schedulers** like db-scheduler, Quartz, or custom job runners
- **Test domain-specific behavior** that isn't covered by built-in components
- **Advance time** or control time-bounded operations
- **Access custom application components** during tests

## Core Concepts

### PluggedSystem Interface

All Stove systems implement the `PluggedSystem` interface:

```kotlin
interface PluggedSystem : AutoCloseable {
    val testSystem: TestSystem
}
```

### Lifecycle Interfaces

Stove provides several lifecycle interfaces your system can implement:

| Interface | Method | When Called |
|-----------|--------|-------------|
| `RunAware` | `run()` | Before application starts |
| `AfterRunAware` | `afterRun()` | After application starts |
| `AfterRunAwareWithContext<T>` | `afterRun(context: T)` | After application starts, with DI context |
| `ExposesConfiguration` | `configuration()` | When collecting app configuration |

### Registration Functions

To make your system available in the DSL, you need:

1. **Registration function** - Adds system to TestSystem
2. **Getter function** - Retrieves system from TestSystem  
3. **DSL extension functions** - For `WithDsl` and `ValidationDsl`

## Example 1: Db-Scheduler Integration

Here's a complete example of integrating with [db-scheduler](https://github.com/kagkarlsson/db-scheduler):

### Step 1: Create the Event Listener

First, create a listener that captures scheduler events:

```kotlin
import com.github.kagkarlsson.scheduler.task.ExecutionComplete
import com.github.kagkarlsson.scheduler.task.TaskInstanceId
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentMap
import kotlin.reflect.KClass
import kotlin.time.Duration
import kotlinx.coroutines.*

class StoveDbSchedulerListener : AbstractSchedulerListener() {
    private val completedExecutions: ConcurrentMap<String, ExecutionComplete> = ConcurrentHashMap()
    private val scheduledExecutions: ConcurrentMap<String, Instant> = ConcurrentHashMap()

    override fun onExecutionComplete(executionComplete: ExecutionComplete) {
        completedExecutions[executionComplete.execution.taskInstance.id] = executionComplete
    }

    override fun onExecutionScheduled(taskInstanceId: TaskInstanceId, executionTime: Instant) {
        scheduledExecutions[taskInstanceId.id] = executionTime
    }

    @Suppress("UNCHECKED_CAST")
    suspend fun <T : Any> waitUntilObserved(
        atLeastIn: Duration,
        clazz: KClass<T>,
        condition: (T) -> Boolean
    ) = coroutineScope {
        val getExecutions = { completedExecutions.map { it.value } }
        val getExecutionData = { getExecutions().mapNotNull { it.execution.taskInstance?.data } }
        
        getExecutionData.waitUntilConditionMet(atLeastIn, "While OBSERVING ${clazz.java.simpleName}") {
            when {
                clazz.java.isAssignableFrom(it.javaClass) -> condition(it as T)
                else -> false
            }
        }
    }

    private suspend fun <T> (() -> Collection<T>).waitUntilConditionMet(
        duration: Duration,
        subject: String,
        condition: (T) -> Boolean
    ): Collection<T> = runCatching {
        val collectionFunc = this
        withTimeout(duration) {
            while (!collectionFunc().any { condition(it) }) {
                delay(50)
            }
        }
        return collectionFunc().filter { condition(it) }
    }.recoverCatching {
        when (it) {
            is TimeoutCancellationException -> throw AssertionError("GOT A TIMEOUT: $subject.")
            is ConcurrentModificationException -> 
                Result.success(waitUntilConditionMet(duration, subject, condition))
            else -> throw it
        }.getOrThrow()
    }.getOrThrow()
}
```

### Step 2: Create the System

Create the system class that integrates with Stove:

```kotlin
import com.trendyol.stove.testing.e2e.system.TestSystem
import com.trendyol.stove.testing.e2e.system.abstractions.*
import org.springframework.context.ApplicationContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.coroutineScope

class DbSchedulerSystem(
    override val testSystem: TestSystem
) : PluggedSystem, AfterRunAwareWithContext<ApplicationContext> {
    
    lateinit var listener: StoveDbSchedulerListener

    override suspend fun afterRun(context: ApplicationContext) {
        // Get the listener bean from Spring context
        listener = context.getBean(StoveDbSchedulerListener::class.java)
    }

    /**
     * Assert that a task was executed with the given condition.
     */
    suspend inline fun <reified T : Any> shouldBeExecuted(
        atLeastIn: Duration = 5.seconds,
        noinline condition: T.() -> Boolean
    ): DbSchedulerSystem = coroutineScope {
        listener.waitUntilObserved(atLeastIn, T::class, condition)
    }.let { this }

    override fun close() {
        // Cleanup if needed
    }
}
```

### Step 3: Create DSL Extensions

Create extension functions for the Stove DSL:

```kotlin
import arrow.core.getOrElse
import com.trendyol.stove.testing.e2e.system.*
import com.trendyol.stove.testing.e2e.system.abstractions.*
import com.trendyol.stove.testing.e2e.system.annotations.StoveDsl

/**
 * Registers the DbSchedulerSystem with TestSystem.
 */
@StoveDsl
fun TestSystem.withDbSchedulerListener(): TestSystem = 
    getOrRegister(DbSchedulerSystem(this)).let { this }

/**
 * Gets the DbSchedulerSystem from TestSystem.
 */
@StoveDsl
fun TestSystem.dbScheduler(): DbSchedulerSystem = 
    getOrNone<DbSchedulerSystem>().getOrElse { 
        throw SystemNotRegisteredException(DbSchedulerSystem::class) 
    }

/**
 * Configuration DSL extension.
 */
@StoveDsl
fun WithDsl.dbScheduler(): TestSystem = 
    this.testSystem.withDbSchedulerListener()

/**
 * Validation DSL extension.
 */
@StoveDsl
suspend fun ValidationDsl.tasks(
    validation: suspend DbSchedulerSystem.() -> Unit
): Unit = validation(this.testSystem.dbScheduler())
```

### Step 4: Register the Listener Bean

In your test setup, register the listener as a Spring bean using `addTestDependencies`:

```kotlin
import com.trendyol.stove.testing.e2e.addTestDependencies

runApplication<MyApp>(*params) {
    addTestDependencies {
        bean<StoveDbSchedulerListener>(isPrimary = true)
    }
}
```

### Step 5: Use in Tests

```kotlin
// Configuration
TestSystem()
    .with {
        httpClient { HttpClientSystemOptions(...) }
        postgresql { PostgresqlOptions(...) }
        dbScheduler()  // Register the custom system
        
        springBoot(
            runner = { params -> 
                myApp.run(params) { addTestDependencies() }
            }
        )
    }
    .run()

// In tests
test("should execute scheduled task") {
    TestSystem.validate {
        // Trigger task scheduling
        http {
            postAndExpectBodilessResponse("/schedule-task", body = TaskRequest(...).some()) {
                it.status shouldBe 200
            }
        }
        
        // Assert task was executed
        tasks {
            shouldBeExecuted<MyScheduledTaskData>(atLeastIn = 10.seconds) {
                taskId == expectedTaskId && 
                status == "COMPLETED"
            }
        }
    }
}
```

## Example 2: In-Memory Event Capture System

Here's another example for capturing domain events published by your application:

### Step 1: Create Event Listener

```kotlin
import org.springframework.context.event.EventListener
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.reflect.KClass

/**
 * Captures all domain events in memory for testing.
 */
class StoveDomainEventListener {
    private val capturedEvents = ConcurrentLinkedQueue<Any>()

    @EventListener
    fun onEvent(event: Any) {
        capturedEvents.add(event)
    }

    fun getAllEvents(): List<Any> = capturedEvents.toList()

    @Suppress("UNCHECKED_CAST")
    fun <T : Any> getEventsOfType(clazz: KClass<T>): List<T> =
        capturedEvents
            .filter { clazz.java.isAssignableFrom(it.javaClass) }
            .map { it as T }

    fun clear() = capturedEvents.clear()
}
```

### Step 2: Create the System

```kotlin
import com.trendyol.stove.testing.e2e.system.TestSystem
import com.trendyol.stove.testing.e2e.system.abstractions.*
import kotlinx.coroutines.*
import org.springframework.context.ApplicationContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class DomainEventSystem(
    override val testSystem: TestSystem
) : PluggedSystem, AfterRunAwareWithContext<ApplicationContext> {

    private lateinit var listener: StoveDomainEventListener

    override suspend fun afterRun(context: ApplicationContext) {
        listener = context.getBean(StoveDomainEventListener::class.java)
    }

    /**
     * Assert that an event of type T was published matching the condition.
     */
    suspend inline fun <reified T : Any> shouldBePublished(
        atLeastIn: Duration = 5.seconds,
        crossinline condition: T.() -> Boolean
    ): DomainEventSystem = coroutineScope {
        waitUntilEventObserved(atLeastIn, T::class) { condition(it) }
        this@DomainEventSystem
    }

    /**
     * Assert that no event of type T was published matching the condition.
     */
    inline fun <reified T : Any> shouldNotBePublished(
        condition: T.() -> Boolean
    ): DomainEventSystem {
        val events = listener.getEventsOfType(T::class)
        val matchingEvents = events.filter { condition(it) }
        if (matchingEvents.isNotEmpty()) {
            throw AssertionError(
                "Expected no ${T::class.simpleName} matching condition, " +
                "but found ${matchingEvents.size}: $matchingEvents"
            )
        }
        return this
    }

    /**
     * Get all captured events of type T.
     */
    inline fun <reified T : Any> getEvents(): List<T> =
        listener.getEventsOfType(T::class)

    /**
     * Clear all captured events.
     */
    fun clearEvents(): DomainEventSystem {
        listener.clear()
        return this
    }

    @Suppress("UNCHECKED_CAST")
    suspend fun <T : Any> waitUntilEventObserved(
        atLeastIn: Duration,
        clazz: KClass<T>,
        condition: (T) -> Boolean
    ): T = withTimeout(atLeastIn) {
        while (true) {
            val events = listener.getEventsOfType(clazz)
            val matching = events.find { condition(it) }
            if (matching != null) {
                return@withTimeout matching
            }
            delay(50)
        }
        @Suppress("UNREACHABLE_CODE")
        throw AssertionError("Should not reach here")
    }

    override fun close() {
        // Cleanup if needed
    }
}
```

### Step 3: Create DSL Extensions

```kotlin
import arrow.core.getOrElse
import com.trendyol.stove.testing.e2e.system.*
import com.trendyol.stove.testing.e2e.system.abstractions.*
import com.trendyol.stove.testing.e2e.system.annotations.StoveDsl

@StoveDsl
fun TestSystem.withDomainEvents(): TestSystem =
    getOrRegister(DomainEventSystem(this)).let { this }

@StoveDsl
fun TestSystem.domainEvents(): DomainEventSystem =
    getOrNone<DomainEventSystem>().getOrElse {
        throw SystemNotRegisteredException(DomainEventSystem::class)
    }

@StoveDsl
fun WithDsl.domainEvents(): TestSystem =
    this.testSystem.withDomainEvents()

@StoveDsl
suspend fun ValidationDsl.domainEvents(
    validation: suspend DomainEventSystem.() -> Unit
): Unit = validation(this.testSystem.domainEvents())
```

### Step 4: Register and Use

```kotlin
import com.trendyol.stove.testing.e2e.addTestDependencies

// Configuration
TestSystem()
    .with {
        httpClient { HttpClientSystemOptions(...) }
        domainEvents()  // Register custom system
        springBoot(
            runner = { params -> 
                runApplication<MyApp>(*params) {
                    addTestDependencies {
                        bean<StoveDomainEventListener>(isPrimary = true)
                    }
                }
            }
        )
    }
    .run()

// Tests
test("should publish UserCreatedEvent when user is created") {
    TestSystem.validate {
        val userId = UUID.randomUUID().toString()
        
        http {
            postAndExpectBody<UserResponse>(
                uri = "/users",
                body = CreateUserRequest(id = userId, name = "John").some()
            ) { response ->
                response.status shouldBe 201
            }
        }
        
        domainEvents {
            shouldBePublished<UserCreatedEvent>(atLeastIn = 5.seconds) {
                this.userId == userId &&
                this.name == "John"
            }
            
            shouldNotBePublished<UserDeletedEvent> {
                this.userId == userId
            }
        }
    }
}
```

## Example 3: Time Control System

Control time-bounded operations in your tests:

```kotlin
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

class StoveTestClock : Clock() {
    @Volatile
    private var instant: Instant = Instant.now()
    private val zone: ZoneId = ZoneId.systemDefault()

    override fun instant(): Instant = instant
    override fun withZone(zone: ZoneId): Clock = this
    override fun getZone(): ZoneId = zone

    fun advance(duration: java.time.Duration) {
        instant = instant.plus(duration)
    }

    fun setTime(newInstant: Instant) {
        instant = newInstant
    }

    fun reset() {
        instant = Instant.now()
    }
}

class TimeSystem(
    override val testSystem: TestSystem
) : PluggedSystem, AfterRunAwareWithContext<ApplicationContext> {

    private lateinit var clock: StoveTestClock

    override suspend fun afterRun(context: ApplicationContext) {
        clock = context.getBean(StoveTestClock::class.java)
    }

    fun advance(duration: kotlin.time.Duration): TimeSystem {
        clock.advance(java.time.Duration.ofMillis(duration.inWholeMilliseconds))
        return this
    }

    fun setTime(instant: Instant): TimeSystem {
        clock.setTime(instant)
        return this
    }

    fun reset(): TimeSystem {
        clock.reset()
        return this
    }

    override fun close() {}
}

// DSL Extensions
@StoveDsl
fun WithDsl.timeControl(): TestSystem = 
    testSystem.getOrRegister(TimeSystem(testSystem)).let { testSystem }

@StoveDsl
suspend fun ValidationDsl.time(
    action: suspend TimeSystem.() -> Unit
): Unit = action(testSystem.getOrNone<TimeSystem>().getOrElse { 
    throw SystemNotRegisteredException(TimeSystem::class) 
})

// Usage in tests
test("should expire session after 30 minutes") {
    TestSystem.validate {
        // Create session and capture session ID
        var sessionId: String = ""
        http {
            postAndExpectBody<SessionResponse>("/login", body = credentials.some()) { response ->
                sessionId = response.body().sessionId 
            }
        }
        
        // Advance time by 31 minutes
        time {
            advance(31.minutes)
        }
        
        // Session should be expired
        http {
            getResponse<ErrorResponse>("/protected", headers = mapOf("Session-ID" to sessionId)) { response ->
                response.status shouldBe 401
            }
        }
    }
}
```

## Implementing ExposesConfiguration

If your system needs to provide configuration to the application:

```kotlin
class MyCustomSystem(
    override val testSystem: TestSystem,
    private val options: MySystemOptions
) : PluggedSystem, RunAware, ExposesConfiguration {

    private lateinit var config: MyExposedConfig

    override suspend fun run() {
        // Initialize and prepare configuration
        config = MyExposedConfig(
            host = "localhost",
            port = findAvailablePort()
        )
    }

    override fun configuration(): List<String> {
        // Return configuration properties for the application
        return options.configureExposedConfiguration(config)
    }

    override fun close() {}
}

// Configuration will be collected and passed to the application
TestSystem()
    .with {
        myCustomSystem {
            MySystemOptions(
                configureExposedConfiguration = { cfg ->
                    listOf(
                        "my.service.host=${cfg.host}",
                        "my.service.port=${cfg.port}"
                    )
                }
            )
        }
    }
    .run()
```

## Extending Existing Systems

Beyond creating entirely new systems, you can also extend existing Stove systems with custom DSL extensions. This is useful when you want to add domain-specific functionality to built-in systems like `HttpSystem`, `KafkaSystem`, etc.

### Example: Adding GraphQL Support to HttpSystem

Here's a complete example of extending `HttpSystem` with GraphQL query capabilities:

#### Step 1: Define Response Types

```kotlin
import com.fasterxml.jackson.databind.JsonNode

/**
 * Represents a GraphQL error from the response.
 */
data class GraphQLError(
    val message: String,
    val locations: List<Map<String, Int>>? = null,
    val path: List<Any?>? = null,
    val extensions: Map<String, Any?>? = null
)

/**
 * The standard GraphQL response envelope.
 */
data class GraphQLEnvelope(
    val data: JsonNode?,
    val errors: List<GraphQLError> = emptyList()
)
```

#### Step 2: Create Extension Functions

```kotlin
import com.trendyol.stove.testing.e2e.http.HttpSystem
import com.trendyol.stove.testing.e2e.system.annotations.StoveDsl
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*

/**
 * Executes a GraphQL query/mutation and deserializes the response.
 *
 * @param operationName The name of the GraphQL operation (must match the query/mutation name)
 * @param body The complete GraphQL request body as JSON string
 * @param token Optional authorization token
 * @param assert Assertion function to validate the response
 */
@StoveDsl
suspend inline fun <reified T> HttpSystem.graphql(
    operationName: String,
    body: String,
    token: String? = null,
    crossinline assert: (T) -> Unit
) {
    val value: T = executeGraphQL(operationName, body, token)
    assert(value)
}

/**
 * Executes a GraphQL query/mutation expecting it to fail with an error.
 *
 * @param body The complete GraphQL request body as JSON string
 * @param token Optional authorization token
 * @param assert Assertion function to validate the error
 */
@StoveDsl
suspend fun HttpSystem.graphqlExpectingError(
    body: String,
    token: String? = null,
    assert: (GraphQLError) -> Unit
) {
    client { urlBuilder ->
        val url = urlBuilder.apply { appendPathSegments("graphql") }.build().toString()
        
        post(url) {
            contentType(ContentType.Application.Json)
            token?.let { headers { append("Authorization", "Bearer $it") } }
            setBody(body)
        }.let { response ->
            if (response.status != HttpStatusCode.OK) {
                throw AssertionError(
                    "GraphQL operation failed with HTTP error: ${response.status}\n" +
                    "Body:\n${response.bodyAsText()}"
                )
            }
            
            val envelope = objectMapper.readValue(response.bodyAsText(), GraphQLEnvelope::class.java)
            
            if (envelope.errors.isEmpty()) {
                throw AssertionError(
                    "Expected GraphQL errors but got none. Data:\n${envelope.data?.toPrettyString() ?: "<null>"}"
                )
            }
            
            assert(envelope.errors.first())
        }
    }
}

/**
 * Executes a GraphQL query/mutation with dynamic JSON navigation.
 */
@StoveDsl
suspend fun HttpSystem.graphqlDynamic(
    operationName: String,
    body: String,
    token: String? = null,
    assert: (GraphQLNode) -> Unit
) {
    val node = executeGraphQLInternal(operationName, body, token) { fieldNode -> 
        GraphQLNode(fieldNode) 
    }
    assert(node)
}

private suspend inline fun <reified T> HttpSystem.executeGraphQL(
    operationName: String,
    body: String,
    token: String?
): T = executeGraphQLInternal(operationName, body, token) { fieldNode ->
    objectMapper.readValue(fieldNode.toString(), T::class.java)
}

private suspend fun <R> HttpSystem.executeGraphQLInternal(
    operationName: String,
    body: String,
    token: String?,
    decode: (JsonNode) -> R
): R {
    var result: R? = null
    
    client { urlBuilder ->
        val url = urlBuilder.apply { appendPathSegments("graphql") }.build().toString()
        
        post(url) {
            contentType(ContentType.Application.Json)
            token?.let { headers { append("Authorization", "Bearer $it") } }
            setBody(body)
        }.let { response ->
            if (response.status != HttpStatusCode.OK) {
                throw AssertionError(
                    "GraphQL operation($operationName) failed: ${response.status}\n" +
                    "Body:\n${response.bodyAsText()}"
                )
            }
            
            val envelope = objectMapper.readValue(response.bodyAsText(), GraphQLEnvelope::class.java)
            
            if (envelope.errors.isNotEmpty()) {
                val errorText = envelope.errors.joinToString("\n") { e ->
                    "• ${e.message}" + 
                    (e.path?.let { " | path: $it" } ?: "") +
                    (e.extensions?.let { " | ext: $it" } ?: "")
                }
                throw AssertionError(
                    "GraphQL operation($operationName) returned errors:\n$errorText\n\n" +
                    "Data:\n${envelope.data?.toPrettyString() ?: "<null>"}"
                )
            }
            
            val dataNode = envelope.data
                ?: throw AssertionError(
                    "GraphQL operation($operationName) returned no 'data'. " +
                    "Response:\n${response.bodyAsText()}"
                )
            
            val fieldNode = dataNode.get(operationName)
                ?: throw AssertionError(
                    "GraphQL response has no field '$operationName'. " +
                    "Available: ${dataNode.fieldNames().asSequence().toList()}"
                )
            
            result = decode(fieldNode)
        }
    }
    
    return result ?: throw AssertionError("GraphQL operation mapping failed")
}
```

#### Step 3: Create Dynamic Navigation Helper (Optional)

For flexible JSON navigation without strict typing:

```kotlin
import com.fasterxml.jackson.databind.JsonNode
import kotlin.reflect.KProperty1

/**
 * A wrapper for dynamic GraphQL response navigation.
 */
@JvmInline
value class GraphQLNode(val node: JsonNode?) {
    
    fun exists() = node != null && !node.isNull
    
    operator fun get(key: String): GraphQLNode {
        val obj = node ?: error("Expected object but was <null>")
        if (!obj.isObject) error("Expected object to access key '$key' but was ${obj.nodeType}")
        return GraphQLNode(obj.get(key))
    }
    
    operator fun get(index: Int): GraphQLNode {
        val arr = node ?: error("Expected array but was <null>")
        if (!arr.isArray) error("Expected array to access [$index] but was ${arr.nodeType}")
        return GraphQLNode(arr.get(index))
    }
    
    /**
     * Navigate using dot notation: "user.address.city" or "items[0].name"
     */
    fun at(path: String): GraphQLNode {
        var current = this
        val tokens = parsePath(path)
        for (token in tokens) {
            current = when (token) {
                is PathToken.Key -> current[token.name]
                is PathToken.Index -> current[token.index]
            }
        }
        return current
    }
    
    fun asNodes(): List<GraphQLNode> {
        val arr = node ?: error("Expected array but was <null>")
        if (!arr.isArray) error("Expected array but was ${arr.nodeType}")
        return arr.map { GraphQLNode(it) }
    }
    
    inline fun <reified T> asType(): T {
        val n = node ?: error("Value is <null>, expected ${T::class.simpleName}")
        return when (T::class) {
            String::class -> n.asText() as T
            Int::class -> n.asInt() as T
            Long::class -> n.asLong() as T
            Boolean::class -> n.asBoolean() as T
            Double::class -> n.asDouble() as T
            else -> objectMapper.convertValue(n, T::class.java)
        }
    }
    
    fun string() = asType<String>()
    fun int() = asType<Int>()
    fun long() = asType<Long>()
    fun bool() = asType<Boolean>()
    fun double() = asType<Double>()
    fun raw(): JsonNode? = node
    
    private sealed interface PathToken {
        data class Key(val name: String) : PathToken
        data class Index(val index: Int) : PathToken
    }
    
    private fun parsePath(path: String): List<PathToken> {
        if (path.isBlank()) return emptyList()
        val tokens = mutableListOf<PathToken>()
        var i = 0
        val buffer = StringBuilder()
        
        fun flushKey() {
            if (buffer.isNotEmpty()) {
                tokens += PathToken.Key(buffer.toString())
                buffer.setLength(0)
            }
        }
        
        while (i < path.length) {
            when (val c = path[i]) {
                '.' -> { flushKey(); i++ }
                '[' -> {
                    flushKey()
                    val end = path.indexOf(']', i + 1)
                    val idx = path.substring(i + 1, end).toInt()
                    tokens += PathToken.Index(idx)
                    i = end + 1
                }
                else -> { buffer.append(c); i++ }
            }
        }
        flushKey()
        return tokens
    }
}

// Property-based access extensions
operator fun <T> GraphQLNode.get(prop: KProperty1<*, T>): GraphQLNode = this[prop.name]

inline fun <reified T> GraphQLNode.valueOf(prop: KProperty1<*, T>): T = this[prop].asType()

inline fun <reified T> List<GraphQLNode>.findBy(prop: KProperty1<*, T>, value: T): GraphQLNode? =
    firstOrNull { it.valueOf(prop) == value }

inline fun <reified T> List<GraphQLNode>.requireBy(prop: KProperty1<*, T>, value: T): GraphQLNode =
    findBy(prop, value) ?: error("No element where ${prop.name} == $value")
```

#### Step 4: Use in Tests

```kotlin
data class Product(
    val id: String,
    val name: String,
    val price: Double,
    val category: String
)

data class User(
    val id: String,
    val name: String,
    val email: String
)

test("should query products by category") {
    TestSystem.validate {
        http {
            val query = """
                {
                    "query": "query { productsByCategory(category: \"ELECTRONICS\") { id name price category } }"
                }
            """.trimIndent()
            
            graphql<List<Product>>("productsByCategory", query) { products ->
                products.shouldNotBeEmpty()
                products.forEach { product ->
                    product.category shouldBe "ELECTRONICS"
                }
            }
        }
    }
}

test("should query current user") {
    TestSystem.validate {
        http {
            val query = """
                {
                    "query": "query { me { id name email } }"
                }
            """.trimIndent()
            
            graphql<User>("me", query, token = "user-jwt-token") { user ->
                user.id shouldNotBe null
                user.email shouldContain "@"
            }
        }
    }
}

test("should handle GraphQL errors") {
    TestSystem.validate {
        http {
            val query = """
                {
                    "query": "query { invalidField }"
                }
            """.trimIndent()
            
            graphqlExpectingError(query) { error ->
                error.message shouldContain "Cannot query field"
            }
        }
    }
}

test("should navigate dynamic response") {
    TestSystem.validate {
        http {
            val query = """
                {
                    "query": "query { searchResults { items { id title metadata { tags } } totalCount } }"
                }
            """.trimIndent()
            
            graphqlDynamic("searchResults", query) { result ->
                // Navigate using dot notation
                result["totalCount"].int() shouldBeGreaterThan 0
                
                // Navigate arrays
                val items = result["items"].asNodes()
                items.shouldNotBeEmpty()
                
                // Access nested fields
                val firstItem = result.at("items[0]")
                firstItem["title"].string() shouldNotBe null
                
                // Property-based navigation
                val found = items.findBy(Product::id, "expected-id")
                found shouldNotBe null
            }
        }
    }
}
```

### Benefits of Extending Existing Systems

1. **Reuse existing infrastructure** - No need to create a full system class
2. **Domain-specific DSL** - Create readable, expressive test code
3. **Type safety** - Leverage Kotlin's type system for assertions
4. **Composability** - Combine with other Stove systems seamlessly

### Other Extension Ideas

You can apply this pattern to extend any Stove system:

```kotlin
// Kafka: Custom message publishing with headers
@StoveDsl
suspend fun KafkaSystem.publishWithCorrelationId(
    topic: String,
    message: Any,
    correlationId: String = UUID.randomUUID().toString()
) {
    publish(
        topic = topic,
        message = message,
        headers = mapOf("X-Correlation-ID" to correlationId)
    )
}

// Couchbase: Query with retry logic
@StoveDsl
suspend inline fun <reified T> CouchbaseSystem.shouldQueryWithRetry(
    query: String,
    maxRetries: Int = 3,
    crossinline assertion: (List<T>) -> Unit
) {
    var lastException: Exception? = null
    repeat(maxRetries) {
        try {
            shouldQuery<T>(query, assertion)
            return
        } catch (e: Exception) {
            lastException = e
            delay(500)
        }
    }
    throw lastException ?: AssertionError("Query failed after $maxRetries retries")
}

// PostgreSQL: Insert test data helper
@StoveDsl
suspend fun PostgresqlSystem.insertTestUser(
    id: String = UUID.randomUUID().toString(),
    name: String = "Test User",
    email: String = "test-${UUID.randomUUID()}@example.com"
): String {
    shouldExecute(
        """
        INSERT INTO users (id, name, email) 
        VALUES ('$id', '$name', '$email')
        """.trimIndent()
    )
    return id
}
```

## Best Practices for Custom Systems

### 1. Use Concurrent Data Structures

When capturing data from multiple threads:

```kotlin
private val events = ConcurrentLinkedQueue<Event>()
private val executionMap = ConcurrentHashMap<String, Execution>()
```

### 2. Handle Timeouts Gracefully

Provide meaningful error messages:

```kotlin
suspend fun <T> waitFor(
    duration: Duration,
    description: String,
    condition: () -> T?
): T = withTimeout(duration) {
    while (true) {
        condition()?.let { return@withTimeout it }
        delay(50)
    }
    throw AssertionError("Timeout waiting for: $description")
}
```

### 3. Make Systems Chainable

Return `this` for fluent API:

```kotlin
fun doSomething(): MySystem {
    // operation
    return this
}

// Allows chaining
mySystem {
    doSomething()
        .doSomethingElse()
        .verify { ... }
}
```

### 4. Annotate DSL Functions

Use `@StoveDsl` for IDE support:

```kotlin
@StoveDsl
suspend fun ValidationDsl.mySystem(
    validation: suspend MySystem.() -> Unit
): Unit = validation(this.testSystem.mySystem())
```

### 5. Document Your System

Provide clear KDoc comments:

```kotlin
/**
 * System for testing scheduled task execution.
 *
 * Example usage:
 * ```kotlin
 * tasks {
 *     shouldBeExecuted<MyTask>(atLeastIn = 10.seconds) {
 *         status == "COMPLETED"
 *     }
 * }
 * ```
 */
class DbSchedulerSystem(...)
```

## Summary

Stove offers two powerful ways to extend its functionality:

### Creating New Systems

For integrating with components not covered by built-in systems:

1. **Create a listener/component** that captures the behavior you want to test
2. **Create a System class** implementing `PluggedSystem` and appropriate lifecycle interfaces
3. **Create DSL extensions** for `WithDsl` and `ValidationDsl`
4. **Register beans** in your test initializer
5. **Use in tests** with the fluent DSL

### Extending Existing Systems

For adding domain-specific functionality to built-in systems:

1. **Create extension functions** on existing system classes (e.g., `HttpSystem`, `KafkaSystem`)
2. **Use `@StoveDsl` annotation** for IDE support
3. **Leverage existing infrastructure** without creating new system classes
4. **Compose with other systems** seamlessly

Both approaches make Stove adaptable to virtually any testing scenario in your application, whether you need to integrate with external components like db-scheduler, capture domain events, add GraphQL support, or create custom assertion helpers.
