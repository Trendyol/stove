# Ktor

`stove-ktor` is the starter for applications built on Ktor. Stove starts the real Ktor application and keeps the test setup in one place.

## Dependency

```kotlin
dependencies {
    testImplementation(platform("com.trendyol:stove-bom:$version"))
    testImplementation("com.trendyol:stove")
    testImplementation("com.trendyol:stove-ktor")
}
```

## Application Entrypoint

Expose a reusable `run` function and return the started `Application`:

```kotlin
fun main(args: Array<String>) {
  run(args, shouldWait = true)
}

fun run(
  args: Array<String>,
  shouldWait: Boolean = false,
  applicationOverrides: () -> Module = { module { } }
): Application {
  val config = loadConfiguration<AppConfiguration>(args)

  val applicationEngine = embeddedServer(Netty, port = config.port, host = "localhost") {
    mainModule(config, applicationOverrides)
  }

  applicationEngine.start(wait = shouldWait)
  return applicationEngine.application
}
```

## Minimal Stove Setup

```kotlin
Stove()
  .with {
    ktor(
      runner = { params -> run(params, shouldWait = false) },
      withParameters = listOf("port=8080")
    )
  }
  .run()
```

## What You Get

- real Ktor startup from your own server bootstrap
- `bridge()` support
- easy composition with Kafka, databases, WireMock, tracing, and HTTP assertions

## Example

- [ktor-example](https://github.com/Trendyol/stove/tree/main/examples/ktor-example)
