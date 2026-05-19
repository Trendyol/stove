# Quarkus

Same Stove DSL, Quarkus runtime. One caveat: `bridge()` isn't shipped yet. Drive verification through HTTP, DB queries, and Kafka assertions.

<a class="open-in-wizard" data-fw="quarkus" data-sys="http,postgresql,kafka">Open Quarkus + Postgres + Kafka in wizard</a>

<div class="stove-tldr" markdown>
<span class="stove-tldr-title">Quarkus specifics</span>
1) Keep <code>@QuarkusMain</code> intact. 2) Publish a readiness signal if your app has no HTTP. 3) Bridge unavailable. Use system DSLs (<code>postgresql</code>, <code>kafka</code>, etc.) for state verification.
</div>

## Setup

```kotlin
dependencies {
    testImplementation(platform("com.trendyol:stove-bom:$stoveVersion"))
    testImplementation("com.trendyol:stove")
    testImplementation("com.trendyol:stove-quarkus")
    testImplementation("com.trendyol:stove-extensions-kotest")  // or -junit

    testImplementation("com.trendyol:stove-http")
    testImplementation("com.trendyol:stove-postgres")
    testImplementation("com.trendyol:stove-kafka")
    testImplementation("com.trendyol:stove-wiremock")
    testImplementation("com.trendyol:stove-tracing")
}
```

Keep the normal Quarkus entrypoint:

```kotlin
@QuarkusMain
object QuarkusMainApp {
  @JvmStatic
  fun main(args: Array<String>) {
    Quarkus.run(*args)
  }
}
```

If your app **does not** expose an HTTP endpoint, publish a startup signal so Stove can detect readiness:

```kotlin
@ApplicationScoped
class StoveStartupSignal {
  fun onStart(@Observes event: StartupEvent) =
    System.setProperty("stove.quarkus.ready", "true")

  fun onStop(@Observes event: ShutdownEvent) =
    System.clearProperty("stove.quarkus.ready")
}
```

Minimal `Stove().with { }`:

```kotlin
Stove().with {
    tracing { enableSpanReceiver() }
    quarkus(
        runner = { params -> QuarkusMainApp.main(params) },
        withParameters = listOf("quarkus.http.port=8080")
    )
}.run()
```

## Kafka note

`stove-kafka` + Quarkus Kafka clients need shared classloading. Add to `application.properties`:

```properties
quarkus.class-loading.parent-first-artifacts=org.apache.kafka:kafka-clients
```

Without this, Stove's Kafka interceptor can't attach.

## What you get

- :white_check_mark: Real Quarkus startup via the normal `main`
- :white_check_mark: Tracing, reporting, dashboard, MCP. All integrated
- :x: Bridge. Not yet (verify through systems)

## Verification without Bridge

Instead of `using<OrderService> { ... }`, query state through the systems your app writes to:

```kotlin
test("order is created") {
  val id = UUID.randomUUID().toString()

  stove {
    http {
      post<OrderResponse>("/orders", CreateOrderRequest(id).some()) {
        it.status shouldBe 201
      }
    }

    postgresql {
      shouldQuery<OrderRow>(
        query = "SELECT id, status FROM orders WHERE id = '$id'",
        mapper = { row -> OrderRow(row.string("id"), row.string("status")) }
      ) {
        it.first().status shouldBe "CREATED"
      }
    }

    kafka {
      shouldBePublished<OrderCreated> { actual.id == id }
    }
  }
}
```

## Example

- [quarkus-example](https://github.com/Trendyol/stove/tree/main/examples/quarkus-example)
