# Spring Boot

`stove-spring` is the most familiar starting point for teams already using Spring Boot. It works especially well if you want to combine HTTP assertions with direct bean access through `bridge()`.

## Dependency

```kotlin
dependencies {
    testImplementation(platform("com.trendyol:stove-bom:$version"))
    testImplementation("com.trendyol:stove")
    testImplementation("com.trendyol:stove-spring")
}
```

## Application Entrypoint

Expose a reusable `run(args, init)` function so Stove can call the real app entrypoint:

```kotlin
@SpringBootApplication
class ExampleApp

fun main(args: Array<String>) {
  run(args)
}

fun run(
  args: Array<String>,
  init: SpringApplication.() -> Unit = {}
): ConfigurableApplicationContext = runApplication<ExampleApp>(*args, init = init)
```

## Minimal Stove Setup

```kotlin
Stove()
  .with {
    springBoot(
      runner = { params -> run(params) },
      withParameters = listOf("server.port=8080")
    )
  }
  .run()
```

## What You Get

- Spring Boot startup through the real application entrypoint
- `bridge()` support for bean access
- full access to Stove components such as PostgreSQL, Kafka, WireMock, HTTP, and tracing

## Examples

- [spring-example](https://github.com/Trendyol/stove/tree/main/examples/spring-example)
- [spring-standalone-example](https://github.com/Trendyol/stove/tree/main/examples/spring-standalone-example)
- [spring-4x-example](https://github.com/Trendyol/stove/tree/main/examples/spring-4x-example)
