# Bridge

Reach into your app's DI container from a test. Read internal state, drive domain services, replace beans for the test only. Type-safe, framework-aware.

<div class="stove-tldr" markdown>
<span class="stove-tldr-title">When to use Bridge</span>
You need state or behavior that isn't exposed through HTTP/Kafka/DB surfaces. Verifying internal flags, exercising a domain method directly, swapping <code>Clock</code> for deterministic time, capturing emitted domain events. If a public surface tells you the answer, prefer that instead.
</div>

!!! warning "Quarkus"
    Bridge isn't shipped for `stove-quarkus` (CDI lifecycle). Drive verification through HTTP, DB, Kafka, gRPC.

## Setup

Built into the framework starters. No extra dependency.

```kotlin
Stove().with {
    bridge()
    springBoot(runner = { params -> com.app.run(params) })
}.run()
```

Ktor auto-detects Koin vs Ktor-DI vs custom (see [Ktor guide](../frameworks/ktor.md#bridge-automatic-di-detection)).

## The `using<T> { }` DSL

Inside `stove { }`, request beans from the AUT:

```kotlin
stove {
  using<OrderRepository> {
    val all = findAll()
    all shouldHaveSize 1
  }
}
```

Multi-bean fetch. Up to 5 generic args:

```kotlin
stove {
  using<OrderService, PaymentService> { orderSvc, paySvc ->
    orderSvc.place(order)
    paySvc.charge(order.amount)
  }
}
```

The lambda receives beans in declaration order. Generics resolve correctly (`using<List<Validator>> { ... }` returns the bound list).

## Capture values out of the lambda

```kotlin
stove {
  val savedOrder = using<OrderRepository> {
    findById(orderId).orElseThrow()
  }

  savedOrder.status shouldBe "CREATED"
}
```

`using<T>` returns whatever the lambda returns.

## Register test-only beans

Inject test doubles or interceptors at runner setup time. Pattern depends on framework + version.

=== "Spring Boot 2.x / 3.x"

    ```kotlin
    springBoot(
      runner = { params ->
        com.app.run(params) {
          addTestDependencies {
            bean<TestSystemKafkaInterceptor<*, *>>(isPrimary = true)
            bean { StoveSerde.jackson.anyByteArraySerde() }
            bean<Clock>(isPrimary = true) { FixedClock(Instant.parse("2026-05-19T10:00:00Z")) }
          }
        }
      }
    )
    ```

=== "Spring Boot 4.x"

    ```kotlin
    springBoot(
      runner = { params ->
        runApplication<MyApp>(*params) {
          addTestDependencies4x {
            registerBean<TestSystemKafkaInterceptor<*, *>>(primary = true)
            registerBean { StoveSerde.jackson.anyByteArraySerde() }
          }
        }
      }
    )
    ```

=== "Ktor (Koin)"

    ```kotlin
    ktor(runner = { params ->
      com.app.run(params, shouldWait = false, testModules = listOf(
        module {
          single<Clock>(override = true) { FixedClock(/* ... */) }
        }
      ))
    })
    ```

=== "Ktor (Ktor-DI)"

    ```kotlin
    ktor(runner = { params ->
      com.app.run(params, shouldWait = false) {
        provide<Clock> { FixedClock(/* ... */) }
      }
    })
    ```

=== "Micronaut"

    ```kotlin
    micronaut(runner = { params ->
      com.app.run(params) {
        registerSingleton(Clock::class.java, FixedClock(/* ... */), Qualifiers.byName("clock"))
      }
    })
    ```

## Real use cases

<div class="stove-catalog">
  <div class="stove-sys-card">
    <div class="stove-sys-card-head"><strong>Test data setup</strong></div>
    <p class="stove-sys-card-desc">Seed via the app's own repository so all invariants apply.</p>
  </div>
  <div class="stove-sys-card">
    <div class="stove-sys-card-head"><strong>State verification</strong></div>
    <p class="stove-sys-card-desc">Inspect cache contents, in-memory counters, processed-message lists.</p>
  </div>
  <div class="stove-sys-card">
    <div class="stove-sys-card-head"><strong>Domain service drive</strong></div>
    <p class="stove-sys-card-desc">Call a service directly when there's no HTTP path (scheduled jobs, listeners).</p>
  </div>
  <div class="stove-sys-card">
    <div class="stove-sys-card-head"><strong>Time control</strong></div>
    <p class="stove-sys-card-desc">Inject <code>Clock</code> / <code>TimeProvider</code> for deterministic tests.</p>
  </div>
  <div class="stove-sys-card">
    <div class="stove-sys-card-head"><strong>Event capture</strong></div>
    <p class="stove-sys-card-desc">Register an <code>@EventListener</code> to assert on domain events.</p>
  </div>
  <div class="stove-sys-card">
    <div class="stove-sys-card-head"><strong>Trigger background work</strong></div>
    <p class="stove-sys-card-desc">Manually advance a scheduled job, drain a retry queue, etc.</p>
  </div>
</div>

### Time-controlled example

```kotlin
class FixedClock(private val instant: Instant) : Clock() {
  override fun getZone() = ZoneOffset.UTC
  override fun withZone(zone: ZoneId) = this
  override fun instant() = instant
}

// Test
test("order expires after 24h") {
  stove {
    val clock = using<Clock> { this }

    http { post("/orders", body) { it.status shouldBe 201 } }

    (clock as FixedClock).advance(Duration.ofHours(25))

    using<OrderService> {
      getOrder(orderId).status shouldBe "EXPIRED"
    }
  }
}
```

### Domain event capture

```kotlin
@Component
class TestEventCaptor {
  val captured = ConcurrentLinkedQueue<DomainEvent>()
  @EventListener fun on(e: DomainEvent) { captured.add(e) }
}

// Register in test dependencies, then assert:
stove {
  http { post("/orders", body) { it.status shouldBe 201 } }

  using<TestEventCaptor> {
    eventually(10.seconds) {
      captured.any { it is OrderCreated && it.id == orderId }
    }
  }
}
```

## When NOT to use Bridge

<div class="stove-pair" markdown="0">
  <div class="stove-do">
**Drive through HTTP / Kafka / DB if you can.** That's what production uses.

```kotlin
http { post<OrderResponse>("/orders", body) { /* assert */ } }
kafka {
  shouldBePublished<OrderCreated> {
    actual.id == id
  }
}
```

  </div>
  <div class="stove-dont">
**Don't bypass the API to "set up" production state.**

```kotlin
using<OrderRepository> { save(prebuiltOrder) }
http { get("/orders/$id") { /* assert */ } }
```

You're testing the test setup, not the app.
  </div>
</div>

## Troubleshooting

| Symptom | Check |
|---|---|
| `bridge()` not available | Quarkus isn't supported yet; use HTTP/DB/Kafka assertions |
| `NoSuchBeanDefinitionException` | Bean exists in production code? Type matches the bound interface? |
| Ktor: wrong DI container detected | Pass explicit resolver: `bridge { app, type -> myContainer.resolve(type) }` |
| Test bean not overriding production | Spring 2.x/3.x: `isPrimary = true`. Spring 4.x: `primary = true` |
| Generics not resolving | Use `using<List<T>> { }` form; Stove resolves via Kotlin reified types |

## Related

- [Spring Boot guide](../frameworks/spring-boot.md)
- [Ktor guide](../frameworks/ktor.md)
- [Micronaut guide](../frameworks/micronaut.md)
- [Best practices · anti-patterns](../best-practices.md#anti-patterns-to-retire)
