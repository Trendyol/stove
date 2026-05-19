# Getting Started

Boot your real app with real dependencies in five steps. Each step shows what changes, why it matters, and how to verify it worked.

<div class="stove-tldr" markdown>
<span class="stove-tldr-title">If you'd rather click</span>
The [Setup Wizard](wizard.md) generates the dependency block, `StoveConfig.kt`, and a runnable sample test from your selections. This page is the manual path with the *why*.
</div>

## Prerequisites

<div class="stove-ribbon" markdown="0">
  <div class="stove-ribbon-item">
    <div class="icon">☕</div>
    <strong>JDK 17+</strong>
    <p>Required for Stove and all starters.</p>
  </div>
  <div class="stove-ribbon-item">
    <div class="icon">🐳</div>
    <strong>Docker</strong>
    <p>For Testcontainers (default). Skip if you use <a href="Components/11-provided-instances/">Provided Instances</a>.</p>
  </div>
  <div class="stove-ribbon-item">
    <div class="icon">📦</div>
    <strong>Gradle (recommended)</strong>
    <p>Examples use Gradle Kotlin DSL. Maven works for deps; the <code>stoveTracing</code> plugin needs Gradle.</p>
  </div>
  <div class="stove-ribbon-item">
    <div class="icon">🧪</div>
    <strong>Kotest 6.1.3+ or JUnit Jupiter 6.x</strong>
    <p>Either test framework. Kotest gets first-class wiring.</p>
  </div>
</div>

!!! tip "IDE setup"
    IntelliJ IDEA + the Kotest plugin = run buttons on every `test {}` block. Worth installing on day one.

## The five steps

<ol class="stove-timeline" markdown="block">
<li markdown="block">

<span class="stove-step-tag">deps</span>
### Add the minimum dependencies

Start with the smallest set that proves the wiring works: BOM + core + one starter + one test extension + `stove-http`. Add more later.

!!! tip "Gradle recommended"
    All examples use Gradle (Kotlin DSL). Maven works for Stove dependencies; the **`stoveTracing`** Gradle plugin is the easiest path to OTel tracing and is the recommended setup for observability.

```kotlin
dependencies {
    testImplementation(platform("com.trendyol:stove-bom:$stoveVersion"))
    testImplementation("com.trendyol:stove")
    testImplementation("com.trendyol:stove-spring")            // or -ktor / -micronaut / -quarkus
    testImplementation("com.trendyol:stove-extensions-kotest") // or -extensions-junit
    testImplementation("com.trendyol:stove-http")
}
```

!!! info "Version alignment"
    Keep the BOM, every `com.trendyol:stove-*`, and `stove-cli` (if you use the dashboard) on the same version. Check [Releases](https://github.com/Trendyol/stove/releases).

Components are á-la-carte. Add what you actually use:

| Module | Use for |
|---|---|
| `stove-kafka`, `stove-spring-kafka` | event flows |
| `stove-postgres`, `stove-mysql`, `stove-mssql`, `stove-mongodb`, `stove-couchbase`, `stove-cassandra`, `stove-redis`, `stove-elasticsearch` | persistence |
| `stove-wiremock`, `stove-grpc-mock` | external surface mocks |
| `stove-grpc` | gRPC client |
| `stove-tracing`, `stove-dashboard` | observability |

!!! tip "Enable tracing with the Gradle plugin"
    `stove-tracing` is wired by the **`com.trendyol.stove.tracing`** Gradle plugin. It attaches the OpenTelemetry Java agent to your test JVM, starts an OTLP gRPC receiver, and exposes the endpoint to your AUT. Zero app-code changes.

    ```kotlin
    plugins { id("com.trendyol.stove.tracing") version "$stoveVersion" }

    stoveTracing {
        serviceName.set("my-service")
        testTaskNames.set(listOf("e2eTest"))
    }
    ```

    Failures then come with a full call chain inside your app. See [Tracing](Components/15-tracing.md) and [When a test fails](observability/when-it-fails.md).

</li>
<li markdown="block">

<span class="stove-step-tag">app</span>
### Expose a reusable entrypoint

Stove boots your app from tests. Extract startup into a `run(args)` function that both `main` and Stove can call.

=== "Spring Boot"

    ```kotlin
    @SpringBootApplication
    class MyApplication

    fun main(args: Array<String>) = run(args)

    fun run(
        args: Array<String>,
        init: SpringApplication.() -> Unit = {}
    ): ConfigurableApplicationContext =
        runApplication<MyApplication>(*args, init = init)
    ```

=== "Ktor (Koin)"

    ```kotlin
    object MyApp {
        @JvmStatic fun main(args: Array<String>) = run(args)

        fun run(
            args: Array<String>,
            wait: Boolean = true,
            testModules: List<Module> = emptyList()
        ): Application = embeddedServer(Netty, port = args.getPort()) {
            install(Koin) { modules(appModule, *testModules.toTypedArray()) }
            configureRouting()
        }.start(wait = wait).application
    }
    ```

=== "Ktor (Ktor-DI)"

    ```kotlin
    object MyApp {
        @JvmStatic fun main(args: Array<String>) = run(args)

        fun run(
            args: Array<String>,
            wait: Boolean = true,
            testDependencies: (DependencyRegistrar.() -> Unit)? = null
        ): Application = embeddedServer(Netty, port = args.getPort()) {
            install(DI) {
                dependencies {
                    provide<MyService> { MyServiceImpl() }
                    testDependencies?.invoke(this)
                }
            }
            configureRouting()
        }.start(wait = wait).application
    }
    ```

=== "Micronaut"

    ```kotlin
    fun main(args: Array<String>) = run(args)

    fun run(
        args: Array<String>,
        init: ApplicationContext.() -> Unit = {}
    ): ApplicationContext = ApplicationContext.builder()
        .args(*args)
        .build()
        .also(init)
        .start()
        .also { ctx ->
            ctx.findBean(EmbeddedApplication::class.java).ifPresent { app ->
                if (!app.isRunning) app.start()
            }
        }
    ```

=== "Quarkus"

    ```kotlin
    @QuarkusMain
    object QuarkusMainApp {
        @JvmStatic fun main(args: Array<String>) { Quarkus.run(*args) }
    }

    @ApplicationScoped
    class StoveStartupSignal {
        fun onStart(@Observes event: StartupEvent) =
            System.setProperty("stove.quarkus.ready", "true")
        fun onStop(@Observes event: ShutdownEvent) =
            System.clearProperty("stove.quarkus.ready")
    }
    ```

    Quarkus needs a startup signal if your app has no HTTP endpoint Stove can probe. See the [Quarkus guide](frameworks/quarkus.md) for full details.

</li>
<li markdown="block">

<span class="stove-step-tag">config</span>
<a id="step-3-create-test-configuration"></a>
### Configure Stove once per suite

Put e2e tests in a dedicated `src/test-e2e/` source set ([why](best-practices.md#use-dedicated-source-set-for-e2e-tests)). `AbstractProjectConfig.beforeProject()` runs once for the entire suite. Set up Stove there, tear it down in `afterProject()`.

!!! info "Kotest 6.x discovery"
    `AbstractProjectConfig` is **not** auto-scanned in Kotest 6.x. Add `src/test-e2e/resources/kotest.properties`:
    ```properties
    kotest.framework.config.fqn=com.myapp.e2e.TestConfig
    ```

=== "Kotest"

    ```kotlin hl_lines="9 11 12 14"
    class TestConfig : AbstractProjectConfig() {
        override val extensions: List<Extension> = listOf(StoveKotestExtension())

        override suspend fun beforeProject() {
            Stove().with {
                httpClient {
                    HttpClientSystemOptions(baseUrl = "http://localhost:8080")
                }

                // Swap springBoot for ktor / micronaut / quarkus
                springBoot(
                    runner = { params -> com.myapp.run(params) },
                    withParameters = listOf("server.port=8080", "logging.level.root=warn")
                )
            }.run()
        }

        override suspend fun afterProject() = Stove.stop()
    }
    ```

=== "JUnit"

    ```kotlin
    @ExtendWith(StoveJUnitExtension::class)
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    abstract class BaseE2ETest {
        companion object {
            @JvmStatic @BeforeAll
            fun setup() = runBlocking {
                Stove().with {
                    httpClient { HttpClientSystemOptions(baseUrl = "http://localhost:8080") }
                    springBoot(
                        runner = { params -> com.myapp.run(params) },
                        withParameters = listOf("server.port=8080")
                    )
                }.run()
            }

            @JvmStatic @AfterAll
            fun teardown() = runBlocking { Stove.stop() }
        }
    }
    ```

</li>
<li markdown="block">

<span class="stove-step-tag">first test</span>
### Write the first assertion

The DSL is `stove { http { ... } }`. Read the result, assert against it. That's it.

=== "Kotest"

    ```kotlin
    class MyFirstE2ETest : FunSpec({
      test("GET /hello returns greeting") {
        stove {
          http {
            get<String>("/hello") { body ->
              body shouldBe "Hello, World!"
            }
          }
        }
      }
    })
    ```

=== "JUnit"

    ```kotlin
    class MyFirstE2ETest : BaseE2ETest() {
      @Test
      fun `GET hello returns greeting`() = runBlocking {
        stove {
          http {
            get<String>("/hello") { body -> body shouldBe "Hello, World!" }
          }
        }
      }
    }
    ```

Run it:

```bash
./gradlew e2eTest                                # all e2e tests
./gradlew e2eTest --tests "com.myapp.e2e.*Test"  # filter
```

</li>
<li markdown="block">

<span class="stove-step-tag">grow</span>
### Add the systems your app actually uses

The same `.with { }` block composes more systems. Below: HTTP in, Kafka events, Couchbase persistence, WireMock for outbound calls, and `bridge()` for DI access.

```kotlin hl_lines="3 8 16 27 30"
Stove().with {
    httpClient { HttpClientSystemOptions(baseUrl = "http://localhost:8080") }

    kafka {
        KafkaSystemOptions { cfg -> listOf(
            "kafka.bootstrapServers=${cfg.bootstrapServers}",
            "kafka.interceptorClasses=${cfg.interceptorClass}"
        ) }
    }

    couchbase {
        CouchbaseSystemOptions(
            defaultBucket = "myBucket",
            configureExposedConfiguration = { cfg -> listOf(
                "couchbase.hosts=${cfg.hostsWithPort}",
                "couchbase.username=${cfg.username}",
                "couchbase.password=${cfg.password}"
            ) }
        )
    }

    wiremock { WireMockSystemOptions(port = 0) }
    bridge()  // DI access for setup + verification

    springBoot(
        runner = { params -> com.myapp.run(params) },
        withParameters = listOf("server.port=8080", "external.service.url=http://localhost:9090")
    )
}.run()
```

Then assert across systems in one test:

```kotlin hl_lines="4 11 18 24"
test("creating an order persists, calls payment, and publishes event") {
  stove {
    val orderId = UUID.randomUUID().toString()

    wiremock { mockPost("/payments", 200, PaymentResult(true).some()) }

    http {
      postAndExpectBody<OrderResponse>(
        uri = "/orders",
        body = CreateOrderRequest(orderId, listOf("item1", "item2"), 99.99).some()
      ) { it.status shouldBe 201 }
    }

    couchbase {
      shouldGet<Order>("orders", orderId) { order ->
        order.status shouldBe "CREATED"
        order.amount shouldBe 99.99
      }
    }

    kafka {
      shouldBePublished<OrderCreatedEvent> {
        actual.orderId == orderId && actual.amount == 99.99
      }
    }

    using<OrderService> { getOrder(orderId).status shouldBe "CREATED" }
  }
}
```

<span data-rn="highlight" data-rn-color="#00968855" data-rn-duration="800">One DSL, every surface that matters.</span>

</li>
</ol>

## Do this, not that

<div class="stove-pair" markdown="0">
  <div class="stove-do">
Generate unique IDs per test run.

```kotlin
val userId = "user-${UUID.randomUUID()}"
```

No collisions, parallel-safe, works against shared infra.
  </div>
  <div class="stove-dont">
Hard-code identifiers.

```kotlin
val userId = "user-1"  // collides on re-run
```

  </div>
</div>

<div class="stove-pair" markdown="0">
  <div class="stove-do">
Wait with timeout, not sleep.

```kotlin
shouldBePublished<E> {
  actual.userId == userId
}
```

  </div>
  <div class="stove-dont">
Block the thread.

```kotlin
Thread.sleep(5_000)  // flaky, slow
kafka { shouldBePublished<E>(...) }
```

  </div>
</div>

<div class="stove-pair" markdown="0">
  <div class="stove-do">
Configure Stove **once** per suite.

```kotlin
override suspend fun beforeProject() = Stove().with { ... }.run()
```

  </div>
  <div class="stove-dont">
Spin Stove up per test.

```kotlin
@BeforeEach fun setup() = Stove().with { ... }.run()  // very slow
```

  </div>
</div>

## Local-loop optimizations

**Keep containers running between runs** during development:

```kotlin hl_lines="2"
Stove {
    keepDependenciesRunning()
}.with { /* ... */ }.run()
```

**Custom registry** (firewalls, private mirrors):

```kotlin
DEFAULT_REGISTRY = "your.registry.com"  // global

kafka {                                  // per component
    KafkaSystemOptions(
        container = KafkaContainerOptions(registry = "your.registry.com")
    )
}
```

## Troubleshooting at a glance

| Symptom | Fix |
|---|---|
| Docker not found | Start Docker Desktop / colima |
| Port conflicts | Use port `0` for mocks; let Stove pick |
| Slow startup | `keepDependenciesRunning()` during dev |
| Serialization errors | Align `StoveSerde` with your app's mapper |
| Tests collide | Generate unique IDs per test |
| Kafka assertion times out | Use [test-friendly Kafka settings](Components/02-kafka.md) |

Deeper [troubleshooting guide](troubleshooting.md) and [best practices](best-practices.md).

## Where to go next

<div class="grid cards" markdown>

-   :material-magic-staff: **Wizard for your stack** · [Open wizard](wizard.md)

-   :material-book-multiple: **Real flows** · [Recipes](recipes/index.md)

-   :material-cog-outline: **Per-framework setup** · [Frameworks](frameworks/index.md)

-   :material-database: **System reference** · [Components](Components/index.md)

-   :material-chart-timeline: **When a test fails** · [Observability story](observability/when-it-fails.md)

-   :material-github: **Working examples** · [GitHub](https://github.com/Trendyol/stove/tree/main/examples)

</div>
