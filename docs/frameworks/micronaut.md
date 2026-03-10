# Micronaut

`stove-micronaut` is the starter for applications built on Micronaut. It uses the same Stove DSL as the other starters.

## Dependency

```kotlin
dependencies {
    testImplementation(platform("com.trendyol:stove-bom:$version"))
    testImplementation("com.trendyol:stove")
    testImplementation("com.trendyol:stove-micronaut")
}
```

## Application Entrypoint

Expose a reusable `run` function that returns the started `ApplicationContext`:

```kotlin
fun main(args: Array<String>) {
  run(args)
}

fun run(
  args: Array<String>,
  init: ApplicationContext.() -> Unit = {}
): ApplicationContext {
  val context = ApplicationContext
    .builder()
    .args(*args)
    .build()
    .also(init)
    .start()

  context.findBean(EmbeddedApplication::class.java).ifPresent { app ->
    if (!app.isRunning) {
      app.start()
    }
  }

  return context
}
```

## Minimal Stove Setup

```kotlin
Stove()
  .with {
    micronaut(
      runner = { params -> run(params) },
      withParameters = listOf("micronaut.server.port=8080")
    )
  }
  .run()
```

## What You Get

- Micronaut startup through the real app context
- `bridge()` support
- clean integration with PostgreSQL, WireMock, Kafka, HTTP, and tracing

## Example

- [micronaut-example](https://github.com/Trendyol/stove/tree/main/examples/micronaut-example)
