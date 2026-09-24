# Writing Your Own Stove System

## Contents
- [Implement PluggedSystem](#1-implement-pluggedsystem)
- [Create a listener](#2-create-a-listener)
- [Write DSL extensions](#3-write-dsl-extensions)
- [Register the listener](#4-register-the-listener)
- [Use in tests](#5-use-in-tests)

The fragments below show the extension points. For the complete listener, polling, imports, and registration, use the [DbSchedulerSystem recipe](https://github.com/Trendyol/stove/blob/main/recipes/jvm/kotlin-recipes/spring-showcase/src/test-e2e/kotlin/com/trendyol/stove/examples/kotlin/spring/e2e/setup/DbSchedulerSystem.kt) at the project's Stove version.

## 1. Implement PluggedSystem

```kotlin
class DbSchedulerSystem(
    override val stove: Stove
) : PluggedSystem,
    AfterRunAwareWithContext<ApplicationContext>,
    Reports {

    lateinit var listener: StoveDbSchedulerListener
    override val reportSystemName: String = "DbScheduler"

    override suspend fun afterRun(context: ApplicationContext) {
        listener = context.getBean()
    }

    override fun snapshot(): SystemSnapshot {
        val completed = if (::listener.isInitialized) listener.getCompletedExecutionsSnapshot() else emptyList()
        return SystemSnapshot(
            system = reportSystemName,
            state = mapOf("completedExecutions" to completed),
            summary = "Completed: ${completed.size} task(s)"
        )
    }

    suspend inline fun <reified T : Any> shouldBeExecuted(
        atLeastIn: Duration = 5.seconds,
        noinline condition: T.() -> Boolean
    ): DbSchedulerSystem = report(
        action = "Assert task executed: ${T::class.simpleName}",
        expected = "Task with ${T::class.simpleName} payload executed".some(),
        metadata = mapOf("timeout" to atLeastIn.toString())
    ) {
        listener.waitUntilObservedSuccessfully(atLeastIn, T::class, condition)
    }.let { this }

    override fun close() = Unit
}
```

### Lifecycle interfaces

| Interface | When Called | Use Case |
|---|---|---|
| `PluggedSystem` | Always (required) | Base interface, provides `close()` |
| `RunAware` | Before app starts | System needs to do setup before the app |
| `AfterRunAware` | After app starts | Runs `suspend fun afterRun()` without a context argument |
| `AfterRunAwareWithContext<T>` | After app starts | Receives app DI container (e.g., `ApplicationContext`) |
| `ExposesConfiguration` | During setup | System exposes config to the application (like containers) |
| `Reports` | During actions and snapshot collection | `report()` records outcomes; `snapshot()` supplies failure reports and dashboard snapshots at test end |

## 2. Create a listener

Observes what happens inside the running application:

```kotlin
class StoveDbSchedulerListener : AbstractSchedulerListener() {
    private val completedExecutions: ConcurrentMap<String, ExecutionComplete> = ConcurrentHashMap()

    override fun onExecutionComplete(executionComplete: ExecutionComplete) {
        completedExecutions[executionComplete.execution.taskInstance.id] = executionComplete
    }

    fun getCompletedExecutionsSnapshot(): List<Map<String, Any?>> =
        completedExecutions.map { (id, execution) ->
            mapOf("instanceId" to id, "result" to execution.result.toString())
        }

    suspend fun <T : Any> waitUntilObservedSuccessfully(
        atLeastIn: Duration, clazz: KClass<T>, condition: (T) -> Boolean
    ): Collection<ExecutionComplete> = TODO("Use bounded polling as in the linked recipe")
}
```

Match events to unique test data and check successful completion, not just arrival. Make snapshots safe before initialization and return copies of concurrent state; reporting can run while the application is active.

## 3. Write DSL extensions

```kotlin
// Registration — used in Stove().with { }
@StoveDsl
fun WithDsl.dbScheduler(): Stove =
    this.stove.getOrRegister(DbSchedulerSystem(this.stove)).let { this.stove }

// Validation — used in stove { }
@StoveDsl
suspend fun ValidationDsl.tasks(validation: suspend DbSchedulerSystem.() -> Unit): Unit =
    validation(this.stove.getOrNone<DbSchedulerSystem>().getOrElse {
        throw SystemNotRegisteredException(DbSchedulerSystem::class)
    })
```

## 4. Register the listener

```kotlin
Stove().with {
    dbScheduler()

    springBoot(
        runner = { params ->
            com.myapp.run(params) {
                addTestDependencies {
                    bean<StoveDbSchedulerListener>(isPrimary = true)
                }
            }
        }
    )
}.run()
```

## 5. Use in tests

```kotlin
stove {
    http { postAndExpectBody<OrderResponse>("/api/orders", body = request.some()) { /* ... */ } }

    tasks {
        shouldBeExecuted<OrderEmailPayload> {
            this.orderId == orderId && this.userId == userId
        }
    }
}
```

**Pattern**: listener captures events -> system exposes assertions -> DSL extensions make it ergonomic -> `report()` integrates with reporting.

## 6. Extending built-in systems

Add domain-specific helpers to existing systems without creating new ones:

```kotlin
@StoveDsl
suspend fun KafkaSystem.publishWithCorrelationId(
    topic: String,
    message: Any,
    correlationId: String = UUID.randomUUID().toString()
) {
    publish(topic, message, headers = mapOf("X-Correlation-ID" to correlationId))
}

// Usage
kafka { publishWithCorrelationId("orders", event, "corr-123") }
```
