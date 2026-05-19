# Ktor

Stove starts your real Ktor server. Works with Koin, Ktor-DI, or any custom container via a one-line resolver.

<a class="open-in-wizard" data-fw="ktor" data-sys="http,postgresql">Open Ktor + Postgres in wizard</a>

<div class="stove-tldr" markdown>
<span class="stove-tldr-title">Two knobs</span>
1) Your <code>run(args, wait = false, ...)</code> returns the started <code>Application</code>. 2) <code>ktor(runner = ...)</code> calls it. Bridge auto-detects Koin vs Ktor-DI; custom containers plug in via a resolver lambda.
</div>

## Anatomy

<div class="stove-anatomy" markdown="0">
  <div class="stove-anatomy-code">
ktor( <span class="anchor">1</span>
  runner = { params ->
    com.app.run( <span class="anchor">2</span>
      params,
      shouldWait = false, <span class="anchor">3</span>
      testModules = listOf(testModule)
    )
  },
  withParameters = listOf("port=8080")
)
  </div>
  <div class="stove-anatomy-notes">
    <div class="stove-note"><span class="stove-note-tag">1</span><strong><code>ktor { }</code></strong> registers Ktor as the AUT runner.</div>
    <div class="stove-note"><span class="stove-note-tag">2</span><strong><code>runner</code></strong> calls your extracted <code>run</code>. Pass test modules / test deps here.</div>
    <div class="stove-note"><span class="stove-note-tag">3</span><strong><code>shouldWait = false</code></strong> is critical. Stove keeps the suite alive itself.</div>
  </div>
</div>

## Setup

```kotlin
dependencies {
    testImplementation(platform("com.trendyol:stove-bom:$stoveVersion"))
    testImplementation("com.trendyol:stove")
    testImplementation("com.trendyol:stove-ktor")
    testImplementation("com.trendyol:stove-extensions-kotest")  // or -junit
}
```

Extract `run` to accept test overrides:

=== "Koin"

    ```kotlin
    fun main(args: Array<String>) = run(args, shouldWait = true).let { Unit }

    fun run(
      args: Array<String>,
      shouldWait: Boolean = false,
      testModules: List<Module> = emptyList()
    ): Application {
      val cfg = loadConfiguration<AppConfiguration>(args)
      return embeddedServer(Netty, port = cfg.port, host = "localhost") {
        install(Koin) { modules(appModule, *testModules.toTypedArray()) }
        configureRouting()
      }.start(wait = shouldWait).application
    }
    ```

=== "Ktor-DI"

    ```kotlin
    fun main(args: Array<String>) = run(args, shouldWait = true).let { Unit }

    fun run(
      args: Array<String>,
      shouldWait: Boolean = false,
      testDependencies: (DependencyRegistrar.() -> Unit)? = null
    ): Application {
      val cfg = loadConfiguration<AppConfiguration>(args)
      return embeddedServer(Netty, port = cfg.port, host = "localhost") {
        install(DI) {
          dependencies {
            provide<MyService> { MyServiceImpl() }
            testDependencies?.invoke(this)
          }
        }
        configureRouting()
      }.start(wait = shouldWait).application
    }
    ```

Minimal `Stove().with { }`:

```kotlin
Stove().with {
    httpClient { HttpClientSystemOptions(baseUrl = "http://localhost:8080") }
    ktor(
        runner = { params -> run(params, shouldWait = false) },
        withParameters = listOf("port=8080")
    )
}.run()
```

## Bridge. Automatic DI detection

| DI framework | Detection | Priority |
|---|---|---|
| Ktor-DI | `dependencies { }` block active | Preferred when both present |
| Koin | `install(Koin) { }` active | Used when Ktor-DI absent |
| Custom (Kodein, Dagger, etc.) | Manual resolver lambda | Explicit override |

### Test overrides

=== "Koin"

    ```kotlin
    Stove().with {
      bridge()
      ktor(runner = { params ->
        run(params, shouldWait = false, testModules = listOf(
          module { single<TimeProvider>(override = true) { FixedTimeProvider() } }
        ))
      })
    }.run()
    ```

=== "Ktor-DI"

    ```kotlin
    Stove().with {
      bridge()
      ktor(runner = { params ->
        run(params, shouldWait = false) {
          provide<TimeProvider> { FixedTimeProvider() }
        }
      })
    }.run()
    ```

=== "Custom resolver"

    ```kotlin
    Stove().with {
      bridge { application, type -> myDiContainer.resolve(type) }
      ktor(runner = { params -> run(params, shouldWait = false) })
    }.run()
    ```

### Using Bridge in tests

```kotlin
stove {
  using<UserService> {
    findById(123).name shouldBe "John"
  }

  using<List<PaymentService>> {
    forEach { it.validate() }
  }
}
```

Full patterns (multi-bean access, value capture, generics): [Bridge reference](../Components/10-bridge.md).

## What you get

- :white_check_mark: Real Netty server, real routing
- :white_check_mark: `bridge()` for Koin **and** Ktor-DI without config
- :white_check_mark: Composes with every Stove system
- :white_check_mark: Hot-swap DI containers via custom resolver

## Common pitfalls

!!! warning "`shouldWait = true` hangs the suite"
    Production `main` waits; tests must not. Always pass `shouldWait = false` from the runner.

!!! warning "DI not detected"
    Bridge looks for `install(Koin)` or `install(DI)`. If you wrap them in feature plugins, expose a custom resolver.

## Example

- [ktor-example](https://github.com/Trendyol/stove/tree/main/examples/ktor-example). Full stack with Koin + Postgres + Kafka
