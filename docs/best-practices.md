# Best Practices

Hard-won patterns from real Stove suites. Not rules. Defaults that pay off.

<div class="stove-tldr" markdown>
<span class="stove-tldr-title">The short list</span>
Dedicated e2e source set · Stove configured once · unique IDs per run · time-bounded waits (no sleep) · mock external boundaries · align serializers · be specific in assertions · clean up shared infra.
</div>

## Test organization

### <a id="use-dedicated-source-set-for-e2e-tests"></a>Use a dedicated source set

Put e2e tests in `src/test-e2e/` so they don't slow down unit tests and can run independently in CI.

```
src/
├── main/kotlin/
├── test/kotlin/                  unit tests
└── test-e2e/kotlin/              Stove tests
    ├── setup/StoveConfig.kt
    ├── features/OrderE2ETest.kt
    └── shared/{TestData,Assertions}.kt
```

Gradle wiring (`build.gradle.kts`):

```kotlin
sourceSets {
    val `test-e2e` by creating {
        compileClasspath += sourceSets.main.get().output
        runtimeClasspath += sourceSets.main.get().output
    }
    configurations["testE2eImplementation"].extendsFrom(configurations.testImplementation.get())
    configurations["testE2eRuntimeOnly"].extendsFrom(configurations.runtimeOnly.get())
}

tasks.register<Test>("e2eTest") {
    description = "Runs e2e tests."
    group = "verification"
    testClassesDirs = sourceSets["test-e2e"].output.classesDirs
    classpath = sourceSets["test-e2e"].runtimeClasspath
    useJUnitPlatform()
    reports { junitXml.required.set(true); html.required.set(true) }
}

idea {
    module {
        testSources.from(sourceSets["test-e2e"].allSource.sourceDirectories)
        testResources.from(sourceSets["test-e2e"].resources.sourceDirectories)
    }
}
```

```bash
./gradlew test          # unit only
./gradlew e2eTest       # e2e only
./gradlew test e2eTest  # both
```

Benefits: isolated runs, CI parallelism, per-suite JVM tuning, clear boundaries.

### Configure Stove once, not per test

<div class="stove-pair" markdown="0">
  <div class="stove-do">

```kotlin
class StoveConfig : AbstractProjectConfig() {
  override suspend fun beforeProject() {
    Stove().with { /* ... */ }.run()
  }
  override suspend fun afterProject() = Stove.stop()
}
```

  </div>
  <div class="stove-dont">

```kotlin
class MyTest : FunSpec({
  beforeSpec {
    Stove().with { /* ... */ }.run()
  }
})
```

Per-test setup re-spins containers. Minutes lost.

  </div>
</div>

## Test data

### Unique IDs per run

<div class="stove-pair" markdown="0">
  <div class="stove-do">

```kotlin
val orderId = UUID.randomUUID().toString()
val userId  = "user-${UUID.randomUUID()}"
```

  </div>
  <div class="stove-dont">

```kotlin
val orderId = "order-123"  // collides on re-run
```

  </div>
</div>

### Shared-infra isolation (CI)

When using [Provided Instances](Components/11-provided-instances.md), prefix every shared resource with a run ID:

```kotlin
object TestRunContext {
    val runId: String = System.getenv("CI_JOB_ID")
        ?: UUID.randomUUID().toString().take(8)
    val databaseName = "testdb_$runId"
    val topicPrefix  = "test_${runId}_"
    val indexPrefix  = "test_${runId}_"
}

Stove().with {
    postgresql { PostgresqlOptions.provided(databaseName = TestRunContext.databaseName, /* ... */) }
    springBoot(withParameters = listOf(
        "kafka.topic.orders=${TestRunContext.topicPrefix}orders",
        "elasticsearch.index.products=${TestRunContext.indexPrefix}products"
    ))
}
```

See [Provided Instances · isolation](Components/11-provided-instances.md#test-isolation-with-shared-infrastructure) for per-system patterns.

### Cleanup hooks

Each system options block accepts `cleanup`. Use it on shared infra; optional for ephemeral containers.

```kotlin
couchbase {
  CouchbaseSystemOptions(
    defaultBucket = "bucket",
    cleanup = { cluster ->
      cluster.query("DELETE FROM `bucket` WHERE type = 'test'")
    },
    configureExposedConfiguration = { cfg -> listOf(/* ... */) }
  )
}

kafka {
  KafkaSystemOptions(
    cleanup = { admin ->
      admin.listTopics().names().get()
        .filter { it.startsWith("test-") }
        .takeIf { it.isNotEmpty() }
        ?.let { admin.deleteTopics(it).all().get() }
    },
    configureExposedConfiguration = { cfg -> listOf(/* ... */) }
  )
}
```

### Test data builders

Centralize defaults so every test reads as the *intent*, not the setup.

```kotlin
object TestData {
  fun user(id: String = UUID.randomUUID().toString(), name: String = "Test User") =
    User(id, name, email = "$id@example.com")
}
```

## Assertions

### Be specific

<div class="stove-pair" markdown="0">
  <div class="stove-do">

```kotlin
http {
  postAndExpectBody<OrderResponse>("/orders", body) {
    it.status shouldBe 201
    it.body().id shouldBe orderId
    it.body().status shouldBe "CREATED"
    it.body().createdAt shouldNotBe null
  }
}
```

  </div>
  <div class="stove-dont">

```kotlin
http {
  postAndExpectBodilessResponse("/orders", body) {
    it.status shouldBe 201   // tells you nothing
  }
}
```

  </div>
</div>

### Verify side effects, not just the response

A complete flow: request → DB row → published event → search index → cache.

```kotlin hl_lines="5 14 22 29 36"
test("order is fully processed") {
  val orderId = UUID.randomUUID().toString()

  stove {
    http {
      postAndExpectBody<OrderResponse>(
        "/orders",
        CreateOrderRequest(orderId).some()
      ) {
        it.status shouldBe 201
      }
    }

    couchbase {
      shouldGet<Order>("orders", orderId) {
        it.status shouldBe "CREATED"
      }
    }

    kafka {
      shouldBePublished<OrderCreatedEvent> {
        actual.orderId == orderId
      }
    }

    elasticsearch {
      shouldGet<Order>(index = "orders", key = orderId) {
        it.status shouldBe "CREATED"
      }
    }

    redis {
      client().connect().sync().get("order:$orderId") shouldNotBe null
    }
  }
}
```

### Wait with timeout, never sleep

<div class="stove-pair" markdown="0">
  <div class="stove-do">

```kotlin
kafka {
  shouldBePublished<Event> {
    actual.id == expectedId
  }
}
```

  </div>
  <div class="stove-dont">

```kotlin
http { post("/async-op") }

Thread.sleep(5_000)   // flaky + slow

kafka {
  shouldBeConsumed<Event> { true }
}
```

  </div>
</div>

## External boundaries

### Mock at the edge with WireMock

<div class="stove-pair" markdown="0">
  <div class="stove-do">

```kotlin
wiremock {
  mockPost("/payments/charge", 200,
    PaymentResult(success = true).some())
}
http { /* drive your app */ }
```

  </div>
  <div class="stove-dont">

Call real third-party services in tests.

Flaky, slow, costs money, can't simulate edge cases.

  </div>
</div>

### Cover error scenarios

```kotlin
test("graceful degradation on payment 500") {
  stove {
    wiremock { mockPost("/payments/charge", 500, ErrorResponse("down").some()) }
    http { postAndExpectBody<OrderResponse>("/orders", req) {
      it.status shouldBe 503
      it.body().status shouldBe "PAYMENT_FAILED"
    } }
  }
}

test("retry on transient 503s") {
  stove {
    wiremock {
      behaviourFor("/payments/charge", WireMock::post) {
        initially { aResponse().withStatus(503) }
        then      { aResponse().withStatus(503) }
        then      { aResponse().withStatus(200)
                      .withBody(it.serialize(PaymentResult(success = true))) }
      }
    }
    http { postAndExpectBody<OrderResponse>("/orders", req) {
      it.status shouldBe 201   // retried and succeeded
    } }
  }
}
```

### External URLs must be configurable

WireMock can't intercept hardcoded URLs.

<div class="stove-pair" markdown="0">
  <div class="stove-do">

```kotlin
@Configuration
class ExternalServicesConfig(
  @Value("\${payment.url}") val paymentUrl: String,
  @Value("\${inventory.url}") val inventoryUrl: String,
)
```

Test wires both to WireMock:

```kotlin
springBoot(withParameters = listOf(
  "payment.url=http://localhost:9090",
  "inventory.url=http://localhost:9090"
))
```

  </div>
  <div class="stove-dont">

```kotlin
class PaymentClient {
  private val url = "http://payment-service.com"
  // WireMock can't intercept this
}
```

  </div>
</div>

## Serialization

Stove's serde must match your app's. Mismatched mappers = mysterious null fields.

```kotlin
val mapper = ObjectMapper().apply {
  registerModule(JavaTimeModule())
  disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
  setSerializationInclusion(JsonInclude.Include.NON_NULL)
}

Stove().with {
  http {
    HttpClientSystemOptions(
      baseUrl = "...",
      contentConverter = JacksonConverter(mapper)
    )
  }

  kafka {
    KafkaSystemOptions(
      serde = StoveSerde.jackson.anyByteArraySerde(mapper)
    ) { cfg -> listOf(/* ... */) }
  }

  wiremock {
    WireMockSystemOptions(
      serde = StoveSerde.jackson.anyByteArraySerde(mapper)
    )
  }
}
```

## Performance

### Keep containers warm during dev

```kotlin hl_lines="2"
Stove {
  keepDependenciesRunning()
}.with { /* ... */ }.run()
```

Disable in CI for clean runs.

### Configure realistic timeouts

```kotlin
http {
  HttpClientSystemOptions(baseUrl = "...", timeout = 30.seconds)
}

kafka {
  shouldBePublished<Event> {
    actual.id == id
  }
}
```

### Parallel-safe by default. Only if data is unique

Already covered above. Same rule scales to parallel test execution.

## Debugging tools

**Verbose logging** when you need it:

```kotlin
springBoot(withParameters = listOf(
  "logging.level.root=debug",
  "logging.level.org.springframework.web=trace"
))
```

**Inspect containers** from inside a test:

```kotlin
stove {
  mongodb {
    val info = inspect()
    println("id=${info?.containerId} ip=${info?.ipAddress}")
  }
}
```

**Reach into DI** via [Bridge](Components/10-bridge.md):

```kotlin
stove {
  using<OrderRepository> { println(findById(orderId)) }
  using<OrderService, PaymentService> { svc, pay -> /* ... */ }
}
```

## CI/CD

### Pick provided over containers when CI has shared infra

```kotlin
val isCI = System.getenv("CI") == "true"

Stove().with {
  kafka {
    if (isCI) {
      KafkaSystemOptions.provided(
        bootstrapServers = System.getenv("KAFKA_SERVERS"),
        configureExposedConfiguration = { cfg ->
          listOf("kafka.bootstrapServers=${cfg.bootstrapServers}")
        }
      )
    } else {
      KafkaSystemOptions { listOf("kafka.bootstrapServers=${it.bootstrapServers}") }
    }
  }
}
```

### Corporate registry mirror

```kotlin
DEFAULT_REGISTRY = System.getenv("DOCKER_REGISTRY") ?: "docker.io"
```

### Resource caps

```kotlin
couchbase {
  CouchbaseSystemOptions(
    container = CouchbaseContainerOptions(
      containerFn = { c ->
        c.withCreateContainerCmdModifier { cmd ->
          cmd.hostConfig?.withMemory(512L * 1024 * 1024)  // 512MB
        }
      }
    )
  ) { /* ... */ }
}
```

## Anti-patterns to retire

<div class="stove-pair" markdown="0">
  <div class="stove-do">
**Test through public APIs.**

```kotlin
http {
  post<OrderResponse>("/orders", body) { /* assertions */ }
}

couchbase {
  shouldGet<Order>("orders", id) { /* assertions */ }
}
```

  </div>
  <div class="stove-dont">
**Don't test by writing directly to repos.**

```kotlin
using<OrderRepository> { save(order) }
shouldGet<Order>(id) { /* ... */ }
```

You're testing your test setup, not the app.
  </div>
</div>

<div class="stove-pair" markdown="0">
  <div class="stove-do">
**Independent tests.**

```kotlin
test("create + get user") {
  val id = createUser()
  getUser(id)
}
```

  </div>
  <div class="stove-dont">
**Shared mutable state.**

```kotlin
var createdId: String? = null
test("create") { createdId = createUser() }
test("get")    { getUser(createdId!!) }  // order-dependent
```

  </div>
</div>

## Summary cheat sheet

| Do | Don't |
|---|---|
| Unique IDs per run | Hardcoded IDs |
| Test through public APIs | Test implementation details |
| Mock external services | Call real third parties |
| `atLeastIn = N.seconds` | `Thread.sleep(...)` |
| Cleanup on shared infra | Leave artifacts |
| Independent tests | Share state between tests |
| Specific assertions on full payload | `status shouldBe 200` and done |
| Test failure paths | Only test happy paths |
| Aligned `StoveSerde` | Mismatched mappers |
| Dedicated `test-e2e` source set | Mixing unit + e2e |
