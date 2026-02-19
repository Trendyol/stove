# Writing Your Own Stove System

Stove is extensible. If your application uses a component Stove doesn't support out of the box, you can write your own system with a native-feeling DSL.

Complete working example: `recipes/kotlin-recipes/spring-showcase/src/test-e2e/kotlin/.../setup/DbSchedulerSystem.kt`

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

    override fun snapshot(): SystemSnapshot = SystemSnapshot(
        system = reportSystemName,
        state = mapOf(
            "completedExecutions" to listener.getCompletedExecutionsSnapshot(),
            "failedExecutions" to listener.getFailedExecutionsSnapshot()
        ),
        summary = "Completed: ${listener.getCompletedExecutionsSnapshot().size} task(s)"
    )

    suspend inline fun <reified T : Any> shouldBeExecuted(
        atLeastIn: Duration = 5.seconds,
        noinline condition: T.() -> Boolean
    ): DbSchedulerSystem = report(
        action = "Assert task executed successfully: ${T::class.simpleName}",
        expected = "Task with ${T::class.simpleName} payload executed successfully".some(),
        metadata = mapOf("timeout" to atLeastIn.toString())
    ) {
        listener.waitUntilObservedSuccessfully(atLeastIn, T::class, condition)
    }.let { this }

    override fun close() = Unit
}
```

Key interfaces:
- `PluggedSystem` — required, marks it as a Stove system
- `AfterRunAwareWithContext<ApplicationContext>` — optional, receives the Spring context after app starts
- `Reports` — optional, contributes to failure reports via `snapshot()`

## 2. Create a listener (or adapter)

The system needs a way to observe what happens inside the application:

```kotlin
class StoveDbSchedulerListener : AbstractSchedulerListener() {
    private val completedExecutions: ConcurrentMap<String, ExecutionComplete> = ConcurrentHashMap()

    override fun onExecutionComplete(executionComplete: ExecutionComplete) {
        completedExecutions[executionComplete.execution.taskInstance.id] = executionComplete
    }

    suspend fun <T : Any> waitUntilObservedSuccessfully(
        atLeastIn: Duration,
        clazz: KClass<T>,
        condition: (T) -> Boolean
    ): Collection<ExecutionComplete> {
        // Poll completedExecutions until a matching entry appears or timeout
    }
}
```

## 3. Write DSL extensions

```kotlin
// Registration DSL — used in Stove().with { }
@StoveDsl
fun WithDsl.dbScheduler(): Stove =
    this.stove.getOrRegister(DbSchedulerSystem(this.stove)).let { this.stove }

// Validation DSL — used in stove { }
@StoveDsl
suspend fun ValidationDsl.tasks(validation: suspend DbSchedulerSystem.() -> Unit): Unit =
    validation(this.stove.dbScheduler())
```

## 4. Register the listener as a test bean

```kotlin
Stove()
    .with {
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
test("should schedule and execute confirmation email task") {
    stove {
        http {
            postAndExpectBody<OrderResponse>(
                uri = "/api/orders",
                body = CreateOrderRequest(userId = userId, amount = 99.99).some()
            ) { response ->
                response.status shouldBe 201
            }
        }

        tasks {
            shouldBeExecuted<OrderEmailPayload> {
                this.orderId == orderId && this.userId == userId
            }
        }
    }
}
```

## Pattern summary

**Listener captures events inside the app** -> **System exposes assertion methods** -> **DSL extensions make it ergonomic** -> **`report()` integrates with Stove reporting**
