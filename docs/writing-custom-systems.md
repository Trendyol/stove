# Writing Custom Systems

Stove's built-in systems cover databases, Kafka, HTTP, gRPC, and more -- but your application is unique. Maybe you use a job scheduler, publish domain events, need to control time in tests, or talk to a service over a custom protocol. Custom systems let you bring **anything** into the Stove DSL so your tests read like this:

```kotlin hl_lines="7-10"
test("should send welcome email after user signs up") {
    stove {
        http {
            post<UserResponse>("/users", createUserRequest) { it.status shouldBe 201 }
        }

        tasks {
            shouldBeExecuted<SendEmailPayload>(atLeastIn = 10.seconds) {
                recipientEmail == "new-user@example.com"
            }
        }
    }
}
```

That `tasks { }` block is a custom system. Building one is straightforward.

## The Pattern

Every custom system has three pieces:

### 1. The System Class

Implement `PluggedSystem` and pick a lifecycle interface that fits your needs:

```kotlin hl_lines="1-2 11-16"
class DbSchedulerSystem(
    override val stove: Stove
) : PluggedSystem, AfterRunAwareWithContext<ApplicationContext> {

    private lateinit var listener: StoveDbSchedulerListener

    override suspend fun afterRun(context: ApplicationContext) {
        listener = context.getBean(StoveDbSchedulerListener::class.java)
    }

    suspend inline fun <reified T : Any> shouldBeExecuted(
        atLeastIn: Duration = 5.seconds,
        noinline condition: T.() -> Boolean
    ): DbSchedulerSystem {
        listener.waitUntilObserved(atLeastIn, T::class, condition)
        return this
    }

    override fun close() {}
}
```

The available lifecycle interfaces are:

| Interface | When Called |
|-----------|------------|
| `RunAware` | Before application starts |
| `AfterRunAware` | After application starts |
| `AfterRunAwareWithContext<T>` | After application starts, with DI context (e.g., Spring `ApplicationContext`) |
| `ExposesConfiguration` | When collecting configuration to pass to the application |

### 2. DSL Extensions

Two extension functions wire your system into Stove's DSL:

```kotlin hl_lines="1-3 5-8"
@StoveDsl
fun WithDsl.dbScheduler(): Stove =
    this.stove.getOrRegister(DbSchedulerSystem(this.stove)).let { this.stove }

@StoveDsl
suspend fun ValidationDsl.tasks(
    validation: suspend DbSchedulerSystem.() -> Unit
): Unit = validation(this.stove.getOrNone<DbSchedulerSystem>().getOrElse {
    throw SystemNotRegisteredException(DbSchedulerSystem::class)
})
```

The first one registers the system during setup (`.with { dbScheduler() }`). The second one exposes it during tests (`tasks { ... }`).

### 3. Bean Registration

If your system needs a component inside the application (like a listener), register it as a test bean:

```kotlin hl_lines="4-6"
springBoot(
    runner = { params ->
        runApplication<MyApp>(*params) {
            addTestDependencies {
                bean<StoveDbSchedulerListener>(isPrimary = true)
            }
        }
    }
)
```

That's the whole pattern. The rest is your domain logic.

## Ideas

Here are examples of what you can build. Each shows the test DSL -- the part your teammates will see -- not the implementation details.

### Scheduled Task Testing

Test that your application scheduled and executed a task with the expected payload:

```kotlin
stove {
    http {
        postAndExpectBodilessResponse("/orders", body = orderRequest.some()) {
            it.status shouldBe 200
        }
    }

    tasks {
        shouldBeExecuted<SendOrderConfirmationPayload>(atLeastIn = 10.seconds) {
            orderId == expectedOrderId && recipientEmail == "customer@example.com"
        }
    }
}
```

!!! note "Full working example"
    See the [spring-showcase recipe](https://github.com/Trendyol/stove/blob/main/recipes/kotlin-recipes/spring-showcase/src/test-e2e/kotlin/com/trendyol/stove/examples/kotlin/spring/e2e/setup/DbSchedulerSystem.kt) for the complete `DbSchedulerSystem` implementation with reporting integration.

### Domain Event Capture

Capture Spring application events in memory and assert on them:

```kotlin
stove {
    http {
        post<UserResponse>("/users", createUserRequest) { it.status shouldBe 201 }
    }

    domainEvents {
        shouldBePublished<UserCreatedEvent>(atLeastIn = 5.seconds) {
            userId == expectedId && name == "John"
        }

        shouldNotBePublished<UserDeletedEvent> {
            userId == expectedId
        }
    }
}
```

The system behind this is a `@EventListener` bean that collects events into a `ConcurrentLinkedQueue`, and a `DomainEventSystem` that polls it with a timeout.

### Time Control

Replace your application's `Clock` with a test-controllable one:

```kotlin
stove {
    http {
        post<SessionResponse>("/login", credentials) { sessionId = it.sessionId }
    }

    time {
        advance(31.minutes)
    }

    http {
        getResponseBodiless("/protected", headers = mapOf("Session-ID" to sessionId)) {
            it.status shouldBe 401  // Session expired
        }
    }
}
```

The system injects a `StoveTestClock` (extending `java.time.Clock`) as a Spring bean, and the `advance()` / `setTime()` methods manipulate it.

### Exposing Configuration

If your system starts infrastructure (like a container) and needs to pass connection details to the application:

```kotlin
class MySystem(
    override val stove: Stove,
    private val options: MySystemOptions
) : PluggedSystem, RunAware, ExposesConfiguration {

    private lateinit var config: MyExposedConfig

    override suspend fun run() {
        config = MyExposedConfig(host = "localhost", port = startContainer())
    }

    override fun configuration(): List<String> =
        options.configureExposedConfiguration(config)

    override fun close() {}
}
```

Stove collects all `configuration()` outputs and passes them to the application as startup parameters.

## Extending Built-In Systems

You don't always need a full system. Sometimes an extension function on an existing system is enough:

```kotlin hl_lines="1-2 15-17"
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

// Usage
kafka {
    publishWithCorrelationId("orders.created", orderEvent)
}
```

This works for any built-in system -- `HttpSystem`, `KafkaSystem`, `PostgresqlSystem`, etc. Use `@StoveDsl` for IDE auto-completion support.
