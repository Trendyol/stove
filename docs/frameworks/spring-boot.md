# Spring Boot

The flagship Stove starter. First-class `bridge()` support, deepest integration, biggest example set.

<a class="open-in-wizard" data-fw="spring-boot" data-sys="http,postgresql,kafka">Open Spring + Postgres + Kafka in wizard</a>

<div class="stove-tldr" markdown>
<span class="stove-tldr-title">Three pieces</span>
1) Add <code>stove-spring</code>. 2) Extract <code>run(args)</code> from your <code>main</code>. 3) Pass it to <code>springBoot(runner = ...)</code>.
</div>

## Anatomy of a Spring Boot setup

<div class="stove-anatomy" markdown="0">
  <div class="stove-anatomy-code">
springBoot( <span class="anchor">1</span>
  runner = { params -> <span class="anchor">2</span>
    com.app.run(params) {
      addTestDependencies { <span class="anchor">3</span>
        bean<TestSystemKafkaInterceptor&lt;*, *&gt;>(isPrimary = true)
        bean { StoveSerde.jackson.anyByteArraySerde() }
      }
    }
  },
  withParameters = listOf( <span class="anchor">4</span>
    "server.port=8080",
    "logging.level.root=warn"
  )
)
  </div>
  <div class="stove-anatomy-notes">
    <div class="stove-note"><span class="stove-note-tag">1</span><strong><code>springBoot { }</code></strong> registers Spring as the AUT runner inside <code>Stove().with { }</code>.</div>
    <div class="stove-note"><span class="stove-note-tag">2</span><strong><code>runner</code></strong> calls your extracted <code>run(args)</code>. Same path production uses.</div>
    <div class="stove-note"><span class="stove-note-tag">3</span><strong><code>addTestDependencies</code></strong> registers test-only beans (Kafka interceptor, custom serde). Use <code>addTestDependencies4x</code> for Spring Boot 4.x.</div>
    <div class="stove-note"><span class="stove-note-tag">4</span><strong><code>withParameters</code></strong> hands properties to <code>SpringApplication</code> before boot.</div>
  </div>
</div>

## Setup in five lines

```kotlin
dependencies {
    testImplementation(platform("com.trendyol:stove-bom:$stoveVersion"))
    testImplementation("com.trendyol:stove")
    testImplementation("com.trendyol:stove-spring")
    testImplementation("com.trendyol:stove-extensions-kotest")  // or -junit
}
```

Extract `run` from `main`:

```kotlin
@SpringBootApplication
class ExampleApp

fun main(args: Array<String>) = run(args)

fun run(
  args: Array<String>,
  init: SpringApplication.() -> Unit = {}
): ConfigurableApplicationContext = runApplication<ExampleApp>(*args, init = init)
```

Minimal `Stove().with { }`:

```kotlin
Stove().with {
    httpClient { HttpClientSystemOptions(baseUrl = "http://localhost:8080") }
    springBoot(
        runner = { params -> com.app.run(params) },
        withParameters = listOf("server.port=8080")
    )
}.run()
```

## Spring Boot 4.x

The bean registration API renamed. Swap `addTestDependencies` for `addTestDependencies4x` and `bean` for `registerBean`:

```kotlin hl_lines="4 5 6"
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

Full bean registration details: [Kafka](../Components/02-kafka.md), [Bridge](../Components/10-bridge.md).

## What you get

- :white_check_mark: Real Spring Boot startup, real `@Bean` graph
- :white_check_mark: `bridge()`. `using<MyRepository> { ... }` reads/writes via real DI
- :white_check_mark: Component access to all Stove systems
- :white_check_mark: Auto-config of test-only beans (Kafka interceptor, custom serde)

## Common pitfalls

!!! warning "Custom `ObjectMapper`?"
    Pass the same instance to Stove's serde. Otherwise dates and enums silently drift. See [Best Practices · Serialization](../best-practices.md#serialization).

!!! warning "App reads `spring.kafka.*` properties but Stove injects `kafka.*`?"
    `configureExposedConfiguration` must produce the property names your app already reads. Check your `KafkaProperties` and mirror those keys.

!!! warning "Bean registration races"
    Use `isPrimary = true` (or 4.x `primary = true`) when overriding a production-registered bean.

## Examples

- [spring-example](https://github.com/Trendyol/stove/tree/main/examples/spring-example). Full stack (HTTP + Postgres + Kafka + WireMock + tracing)
- [spring-standalone-example](https://github.com/Trendyol/stove/tree/main/examples/spring-standalone-example). Minimal smoke test
- [spring-streams-example](https://github.com/Trendyol/stove/tree/main/examples/spring-streams-example). Spring Cloud Stream
- [spring-4x-example](https://github.com/Trendyol/stove/tree/main/examples/spring-4x-example). Spring Boot 4.x patterns
