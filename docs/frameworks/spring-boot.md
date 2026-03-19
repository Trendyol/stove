# Spring Boot

`stove-spring` is the starter for applications built on Spring Boot. It supports `bridge()` for direct bean access in tests.

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

## Spring Boot 4.x

If your application uses Spring Boot 4.x, use `addTestDependencies4x` instead of `addTestDependencies` when registering test beans:

```kotlin
import com.trendyol.stove.addTestDependencies4x

springBoot(
  runner = { params ->
    runApplication<MyApp>(*params) {
      addTestDependencies4x {
        registerBean<TestSystemKafkaInterceptor<*, *>>(primary = true)
        registerBean { StoveSerde.jackson.anyByteArraySerde(yourObjectMapper()) }
      }
    }
  }
)
```

See the [Kafka](../Components/02-kafka.md) and [Bridge](../Components/10-bridge.md) docs for full Spring Boot 4.x bean registration details.

## Examples

- [spring-example](https://github.com/Trendyol/stove/tree/main/examples/spring-example)
- [spring-standalone-example](https://github.com/Trendyol/stove/tree/main/examples/spring-standalone-example)
- [spring-streams-example](https://github.com/Trendyol/stove/tree/main/examples/spring-streams-example)
- [spring-4x-example](https://github.com/Trendyol/stove/tree/main/examples/spring-4x-example)
