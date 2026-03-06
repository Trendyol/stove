# Quarkus

`stove-quarkus` lets Stove start a Quarkus application in the same JVM as your test run, so reporting and `stove-tracing` continue to work with the normal Quarkus `main` entrypoint.

!!! warning "Bridge support"
    `bridge()` is not available in `stove-quarkus` yet.

## Dependency

```kotlin
dependencies {
    testImplementation(platform("com.trendyol:stove-bom:$version"))

    testImplementation("com.trendyol:stove")
    testImplementation("com.trendyol:stove-quarkus")
    testImplementation("com.trendyol:stove-extensions-kotest")

    testImplementation("com.trendyol:stove-http")
    testImplementation("com.trendyol:stove-postgres")
    testImplementation("com.trendyol:stove-kafka")
    testImplementation("com.trendyol:stove-wiremock")
    testImplementation("com.trendyol:stove-tracing")
}
```

## Application Entrypoint

Keep a normal Quarkus entrypoint and let Stove call it from tests:

```kotlin
@QuarkusMain
object QuarkusMainApp {
  @JvmStatic
  fun main(args: Array<String>) {
    Quarkus.run(*args)
  }
}
```

If your application does not expose an HTTP endpoint, publish an explicit startup signal so Stove can detect readiness:

```kotlin
@ApplicationScoped
class StoveStartupSignal {
  fun onStart(@Observes event: StartupEvent) {
    System.setProperty("stove.quarkus.ready", "true")
  }

  fun onStop(@Observes event: ShutdownEvent) {
    System.clearProperty("stove.quarkus.ready")
  }
}
```

## Minimal Stove Setup

```kotlin
Stove()
  .with {
    tracing {
      enableSpanReceiver()
    }

    quarkus(
      runner = { params -> QuarkusMainApp.main(params) },
      withParameters = listOf("quarkus.http.port=8080")
    )
  }
  .run()
```

## Kafka Note

If you use `stove-kafka` with Quarkus Kafka clients, add this to `application.properties`:

```properties
quarkus.class-loading.parent-first-artifacts=org.apache.kafka:kafka-clients
```

This keeps the Kafka client classes shared so Stove's Kafka interceptor bridge can attach correctly.

## Example

- [quarkus-example](https://github.com/Trendyol/stove/tree/main/examples/quarkus-example)
