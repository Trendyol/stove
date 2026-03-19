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

Expose a reusable `run` function and return the started `Application`. The exact shape depends on your DI framework:

=== "Koin"

    ```kotlin
    fun main(args: Array<String>) {
      run(args, shouldWait = true)
    }

    fun run(
      args: Array<String>,
      shouldWait: Boolean = false,
      testModules: List<Module> = emptyList()
    ): Application {
      val config = loadConfiguration<AppConfiguration>(args)

      val applicationEngine = embeddedServer(Netty, port = config.port, host = "localhost") {
        install(Koin) {
          modules(appModule, *testModules.toTypedArray())
        }
        configureRouting()
      }

      applicationEngine.start(wait = shouldWait)
      return applicationEngine.application
    }
    ```

=== "Ktor-DI"

    ```kotlin
    fun main(args: Array<String>) {
      run(args, shouldWait = true)
    }

    fun run(
      args: Array<String>,
      shouldWait: Boolean = false,
      testDependencies: (DependencyRegistrar.() -> Unit)? = null
    ): Application {
      val config = loadConfiguration<AppConfiguration>(args)

      val applicationEngine = embeddedServer(Netty, port = config.port, host = "localhost") {
        install(DI) {
          dependencies {
            provide<MyService> { MyServiceImpl() }
            testDependencies?.invoke(this)
          }
        }
        configureRouting()
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
- `bridge()` support with automatic DI detection
- easy composition with Kafka, databases, WireMock, tracing, and HTTP assertions

## Bridge and DI Support

Ktor Bridge automatically detects which DI framework your application uses at runtime:

| DI Framework | Detection | Priority |
|-------------|-----------|----------|
| **Ktor-DI** | `dependencies { ... }` is active in the application | Preferred when both are present |
| **Koin** | `install(Koin) { ... }` is active | Used when Ktor-DI is not active |
| **Custom** | Manual resolver provided via `bridge { app, type -> ... }` | Explicit override |

### Registering Test Dependencies

=== "Koin"

    Pass test modules that override production beans:

    ```kotlin
    Stove()
      .with {
        bridge()
        ktor(
          runner = { params ->
            run(
              params,
              shouldWait = false,
              testModules = listOf(
                module {
                  single<TimeProvider>(override = true) { FixedTimeProvider() }
                }
              )
            )
          }
        )
      }
      .run()
    ```

=== "Ktor-DI"

    Pass a lambda that registers test overrides (later `provide<T>` calls override earlier ones):

    ```kotlin
    Stove()
      .with {
        bridge()
        ktor(
          runner = { params ->
            run(params, shouldWait = false) {
              provide<TimeProvider> { FixedTimeProvider() }
            }
          }
        )
      }
      .run()
    ```

=== "Custom Resolver"

    For other DI frameworks (Kodein, Dagger, etc.), provide a custom resolver:

    ```kotlin
    Stove()
      .with {
        bridge { application, type ->
          myDiContainer.resolve(type)
        }
        ktor(runner = { params -> run(params, shouldWait = false) })
      }
      .run()
    ```

### Using Bridge in Tests

```kotlin
stove {
  using<UserService> {
    val user = findById(123)
    user.name shouldBe "John"
  }

  using<List<PaymentService>> {
    forEach { service -> service.validate() }
  }
}
```

See the [Bridge documentation](../Components/10-bridge.md) for complete usage patterns including multi-bean access, value capture, and generic type resolution.

## Example

- [ktor-example](https://github.com/Trendyol/stove/tree/main/examples/ktor-example)
