# Writing Custom Systems

Built-in systems cover databases, Kafka, HTTP, gRPC, more. Your app has its own surfaces: job schedulers, domain events, a custom protocol, time control. Wrap them in a Stove system and your tests read like first-class APIs.

<div class="stove-tldr" markdown>
<span class="stove-tldr-title">In 30 seconds</span>
Three pieces. <strong>System class</strong> (implements <code>PluggedSystem</code> + a lifecycle interface), <strong>DSL extensions</strong> (one for registration, one for validation), optional <strong>bean registration</strong> (if your system needs a hook inside the AUT). That's it.
</div>

Goal pattern. `tasks { }` is a custom system you wrote:

```kotlin hl_lines="9 10 11 12"
test("welcome email after signup") {
  stove {
    http {
      post<UserResponse>("/users", createUserRequest) {
        it.status shouldBe 201
      }
    }

    tasks {
      shouldBeExecuted<SendEmailPayload>(atLeastIn = 10.seconds) {
        recipientEmail == "new-user@example.com"
      }
    }
  }
}
```

## The three pieces

### 1. System class

Implement `PluggedSystem` and pick a lifecycle interface for when your code runs:

| Interface | When called |
|---|---|
| `RunAware` | Before AUT starts (spin up infra) |
| `AfterRunAware` | After AUT starts |
| `AfterRunAwareWithContext<T>` | After AUT starts, with DI container (`ApplicationContext`, etc.) |
| `ExposesConfiguration` | When collecting config to pass to AUT |

Example: a db-scheduler-backed task system that reads from the Spring context.

```kotlin hl_lines="1 2 3 7"
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

### 2. DSL extensions

Two extension functions wire it into Stove's DSL. One registers, one validates.

```kotlin
@StoveDsl
fun WithDsl.dbScheduler(): Stove =
  this.stove.getOrRegister(DbSchedulerSystem(this.stove)).let { this.stove }

@StoveDsl
suspend fun ValidationDsl.tasks(
  validation: suspend DbSchedulerSystem.() -> Unit
): Unit = validation(
  this.stove.getOrNone<DbSchedulerSystem>().getOrElse {
    throw SystemNotRegisteredException(DbSchedulerSystem::class)
  }
)
```

Usage:

```kotlin
Stove().with {
  dbScheduler()  // registration
  // ...
}.run()

stove {
  tasks {       // validation
    shouldBeExecuted<SendEmailPayload>(10.seconds) { /* ... */ }
  }
}
```

### 3. Bean registration (optional)

If your system needs a hook inside the app (a listener, an interceptor, a captor), register it as a test bean:

```kotlin
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

That's the entire pattern. Everything else is your domain logic.

## Ideas

<div class="stove-catalog">
  <div class="stove-sys-card">
    <div class="stove-sys-card-head"><strong>Scheduled tasks</strong></div>
    <p class="stove-sys-card-desc">Listen for job executor completion. Assert payloads with timeout.</p>
  </div>
  <div class="stove-sys-card">
    <div class="stove-sys-card-head"><strong>Domain events</strong></div>
    <p class="stove-sys-card-desc">Spring <code>@EventListener</code> into a <code>ConcurrentLinkedQueue</code>. Poll with timeout.</p>
  </div>
  <div class="stove-sys-card">
    <div class="stove-sys-card-head"><strong>Time control</strong></div>
    <p class="stove-sys-card-desc">Inject a <code>StoveTestClock</code> bean. Expose <code>advance()</code> / <code>setTime()</code> in DSL.</p>
  </div>
  <div class="stove-sys-card">
    <div class="stove-sys-card-head"><strong>Custom protocol</strong></div>
    <p class="stove-sys-card-desc">Use <code>RunAware</code> + <code>ExposesConfiguration</code> to spin up infra and feed connection details to the AUT.</p>
  </div>
</div>

### Scheduled tasks (test view)

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

Full reference: [spring-showcase recipe](https://github.com/Trendyol/stove/blob/main/recipes/jvm/kotlin-recipes/spring-showcase/src/test-e2e/kotlin/com/trendyol/stove/examples/kotlin/spring/e2e/setup/DbSchedulerSystem.kt).

### Domain event capture (test view)

```kotlin
stove {
  http {
    post<UserResponse>("/users", createUserRequest) {
      it.status shouldBe 201
    }
  }

  domainEvents {
    shouldBePublished<UserCreatedEvent> {
      userId == expectedId && name == "John"
    }
    shouldNotBePublished<UserDeletedEvent> {
      userId == expectedId
    }
  }
}
```

Implementation: an `@EventListener` bean queues events; the system polls with timeout.

### Time control (test view)

```kotlin
stove {
  http {
    post<SessionResponse>("/login", credentials) {
      sessionId = it.sessionId
    }
  }

  time {
    advance(31.minutes)
  }

  http {
    getResponseBodiless("/protected", headers = mapOf("Session-ID" to sessionId)) {
      it.status shouldBe 401  // session expired
    }
  }
}
```

Implementation: a `StoveTestClock` (extends `java.time.Clock`) injected as a Spring bean. `advance()` / `setTime()` mutate it.

### Exposing configuration

If your system starts infra and needs to hand connection details to the AUT:

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

Stove collects every system's `configuration()` and passes the merged list to your AUT as startup parameters.

## Extending built-in systems

Sometimes a full system is overkill. An extension function on an existing system is enough.

```kotlin
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

Works for any built-in system (`HttpSystem`, `KafkaSystem`, `PostgresqlSystem`, ...). Use `@StoveDsl` for IDE autocomplete + DSL marker safety.

## Related

- [Bridge](Components/10-bridge.md). If all you need is DI access, you may not need a custom system at all
- [Multiple Systems](Components/20-multiple-systems.md). Register multiple keyed instances of your custom system
- [Best Practices](best-practices.md). Patterns that apply to custom systems too
