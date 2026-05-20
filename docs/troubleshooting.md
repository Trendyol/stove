# Troubleshooting

Use this page from the symptom back to the runtime boundary that failed: Docker, dependency startup, application boot,
configuration injection, serializers, async assertions, or observability.

<div class="stove-tldr" markdown>
<span class="stove-tldr-title">Read the failure report first</span>
With the Kotest or JUnit extension registered, failures include the Stove timeline (HTTP calls, database operations,
Kafka observations, WireMock matches) above the assertion failure. Check that report first; it usually identifies which
system saw the last successful operation.
</div>

## Quick lookup

| Symptom | Section |
|---|---|
| Docker not found / not running | [Docker](#docker) |
| Port conflicts | [Docker](#docker) |
| Container slow / OOM | [Containers + memory](#containers-memory) |
| App fails to start | [App startup](#app-startup) |
| Test bean isn't being applied | [App startup](#app-startup) |
| `Timed out waiting for condition` | [Assertion timeouts](#assertion-timeouts) |
| JSON parse / Mismatched input | [Serialization mismatch](#serialization-mismatch) |
| Kafka message never seen | [Kafka assertions silent](#kafka-assertions-silent) |
| WireMock stub never hits | [WireMock not matching](#wiremock-not-matching) |
| Document / row not found | [Data not found](#data-not-found) |
| CI parallel run collisions | [Shared infrastructure](#shared-infrastructure) |
| Dashboard / MCP empty | [Dashboard + MCP](#dashboard-mcp) |
| AI agent can't reach MCP | [Dashboard + MCP](#dashboard-mcp) |
| Old Stove version still in cache | [Migrating versions](#migrating-versions) |

## Docker

### Docker not found / not running

```
Could not find a valid Docker environment
```

| Fix | When |
|---|---|
| Start Docker Desktop / colima / lima | Most common |
| Run `docker info` from the same shell that runs Gradle | Confirms the daemon is reachable from the test process |
| Use [Provided Instances](Components/11-provided-instances.md) | Docker unavailable in CI |

### Port conflicts

```
Address already in use: bind
```

| Fix | Where |
|---|---|
| Use dynamic port `0` for mocks | `WireMockSystemOptions(port = 0)`, `GrpcMockSystemOptions(port = 0)` |
| Stop the other process | `lsof -i :8080` then kill |
| Pick a different fixed port | Last resort |

## Containers + memory

### `OutOfMemoryError`

JVM-side:

```kotlin title="build.gradle.kts"
tasks.test {
    jvmArgs("-Xmx2g", "-Xms512m")
}
```

Container-side (example: Elasticsearch):

```kotlin
elasticsearch {
  ElasticsearchSystemOptions(
    container = ElasticContainerOptions(
      containerFn = { c ->
        c.withEnv("ES_JAVA_OPTS", "-Xms512m -Xmx512m")
      }
    )
  ) { /* config */ }
}
```

For CI, prefer [Provided Instances](Components/11-provided-instances.md) to skip containers entirely.

### Slow container startup

| Cause | Fix |
|---|---|
| Image pull on first run | Pre-pull in CI cache step |
| Dependency initialization time | Increase startup timeout per system, or use `keepDependenciesRunning()` locally |
| Health check slow | Provide an explicit `readinessStrategy` (HTTP / TCP / Probe) |

Keep containers warm during dev:

```kotlin hl_lines="2"
Stove {
  keepDependenciesRunning()
}.with { /* ... */ }.run()
```

Disable in CI for clean runs.

## App startup

### App doesn't start

| Symptom | Fix |
|---|---|
| Spring `BeanCreationException` | Treat it as an application configuration error; inspect the root cause and fix the bean |
| `port already in use` | The app's port matches another process; pass a different `server.port=` via `withParameters` |
| Runner never reaches readiness | For Ktor, start with `wait = false`; for Quarkus, use the documented `Quarkus.run(*args)` startup pattern and readiness signal |

### Test bean override doesn't apply

<div class="stove-pair" markdown="0">
  <div class="stove-do">

```kotlin
// Spring 2.x / 3.x
addTestDependencies {
  bean<TestSystemKafkaInterceptor<*, *>>(isPrimary = true)
}

// Spring 4.x
addTestDependencies4x {
  registerBean<TestSystemKafkaInterceptor<*, *>>(primary = true)
}
```

  </div>
  <div class="stove-dont">

```kotlin
addTestDependencies {
  bean<TestSystemKafkaInterceptor<*, *>>()   // not primary → ignored
}
```

  </div>
</div>

For Ktor, see the [Ktor guide · Bridge auto-detection](frameworks/ktor.md#bridge-automatic-di-detection).

## Assertion timeouts

```
Timed out waiting for condition
```

Check the boundary where Stove expected to observe progress:

1. **The operation actually triggers.** Add a `println` or `using<X> { ... }` inspection to confirm the code path fires.
2. **Async work has time to complete.** Default Stove timeouts are 5–10 seconds; increase per assertion if your async is slow.
3. **Interceptor / bridge is registered.** Without the capture mechanism, Stove can drive the app but cannot observe the event. See [Kafka assertions silent](#kafka-assertions-silent) below.
4. **Topic / collection / table names match production.** Off-by-one names = silent miss.
5. **App's offsets aren't behind.** Add `auto-offset-reset=earliest` for fresh consumers.

## Serialization mismatch

```
JsonParseException: Unrecognized field
MismatchedInputException: Cannot deserialize
Field is unexpectedly null
```

Stove's serde and your app's `ObjectMapper` must agree across HTTP, Kafka, and mocks. Align them:

```kotlin
val mapper = ObjectMapper().apply {
  registerModule(KotlinModule.Builder().build())
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
    ) { /* config */ }
  }
  wiremock {
    WireMockSystemOptions(
      serde = StoveSerde.jackson.anyByteArraySerde(mapper)
    )
  }
}
```

Other gotchas:

| Symptom | Fix |
|---|---|
| Kotlin data class field always null | Add `KotlinModule` + default values, or `@JsonCreator` |
| Field name renamed in JSON | Annotate with `@JsonProperty("exactName")` |
| Date/time fails to parse | `JavaTimeModule` + matching `WRITE_DATES_AS_TIMESTAMPS` flag |

## Kafka assertions silent

```
shouldBePublished<Event>(...) timed out
shouldBeConsumed<Event>(...) timed out
```

Walk through the [Kafka flow widget](Components/02-kafka.md#asserting-published) on the Kafka page; it shows visually where messages pass through the bridge.

Checklist:

1. **Bridge interceptor registered** in your AUT:

    ```kotlin hl_lines="3"
    addTestDependencies {
      bean<TestSystemKafkaInterceptor<*, *>>(isPrimary = true)
    }
    ```

2. **App reads `kafka.interceptorClasses` from properties:**

    ```kotlin hl_lines="6"
    kafka {
      KafkaSystemOptions { cfg ->
        listOf(
          "kafka.bootstrapServers=${cfg.bootstrapServers}",
          "kafka.interceptorClasses=${cfg.interceptorClass}"
        )
      }
    }
    ```

3. **Topic names match production exactly.** Verify with:

    ```kotlin
    kafka {
      shouldBePublished<Event> {
        println("topic=${metadata.topic} payload=$actual")
        true
      }
    }
    ```

4. **Consumer starts from `earliest`** so it doesn't miss a message published before the listener attached:

    ```kotlin
    springBoot(withParameters = listOf(
      "spring.kafka.consumer.auto-offset-reset=earliest",
      "kafka.autoCreateTopics=true"
    ))
    ```

5. **Test-friendly client settings.** Default producer/consumer settings are tuned for production throughput. See [Kafka · test-friendly settings](Components/02-kafka.md#test-friendly-settings).

For non-JVM apps (Go, Python, ...), make sure the [`stove-kafka` bridge](other-languages/go.md) is wired into your producer/consumer.

## WireMock not matching

```
Connection refused to external service
Test timeout when calling mocked endpoint
Mock not found / unexpected request
```

Most WireMock failures are configuration failures: the application under test is still calling the real URL, or a fixed
test URL, instead of the WireMock base URL exposed by Stove.

```kotlin hl_lines="6 7 8 13 14 15"
Stove().with {
  wiremock {
    WireMockSystemOptions(
      port = 0,
      configureExposedConfiguration = { cfg ->
        listOf(
          "payment.service.url=${cfg.baseUrl}",
          "inventory.service.url=${cfg.baseUrl}",
          "notification.service.url=${cfg.baseUrl}"
        )
      }
    )
  }
  springBoot(runner = { params -> com.app.run(params) })
}
```

And your app must read each URL **from a property**, not hardcode it:

```kotlin
@Value("\${payment.service.url}") val paymentUrl: String
```

Debug what WireMock actually received:

```kotlin
wiremock {
  WireMock.getAllServeEvents().forEach { e ->
    println("${e.request.method} ${e.request.url}")
  }
}
```

## Data not found

```
Resource with key (xxx) is not found
Document not found
0 rows returned
```

| Cause | Fix |
|---|---|
| Collection/table name mismatch | Mirror your app's naming exactly |
| Async write hasn't landed | Use a Stove polling assertion, not an immediate raw client read |
| Test cleanup ran early | Cleanup hooks run in `afterProject`, not between tests; if you need per-test isolation, use unique IDs |
| Wrong index (ES). Near-real-time delay | `client().indices().refresh()` before the assertion |

## Shared infrastructure

```
Tests pass locally but fail randomly in CI
Data from another test run appears
"Topic already exists" / "Index already exists"
```

Cause: multiple test runs share resource names. Fix with **unique resource prefixes per run** and pass those names into
the application configuration.

```kotlin
object TestRunContext {
  val runId: String = System.getenv("CI_JOB_ID")
    ?: UUID.randomUUID().toString().take(8)
  val databaseName = "testdb_$runId"
  val topicPrefix  = "test_${runId}_"
  val indexPrefix  = "test_${runId}_"
}
```

Apply everywhere your app addresses these resources:

```kotlin
springBoot(withParameters = listOf(
  "spring.datasource.url=jdbc:postgresql://db:5432/${TestRunContext.databaseName}",
  "kafka.topic.orders=${TestRunContext.topicPrefix}orders",
  "elasticsearch.index.products=${TestRunContext.indexPrefix}products"
))
```

Clean up only what you created:

```kotlin
cleanup = { admin ->
  admin.listTopics().names().get()
    .filter { it.startsWith(TestRunContext.topicPrefix) }
    .takeIf { it.isNotEmpty() }
    ?.let { admin.deleteTopics(it).all().get() }
}
```

Always log the run ID at suite start so CI failures can be mapped back to the exact resources created by that run.

Deep dive: [Provided Instances · isolation](Components/11-provided-instances.md#shared-infrastructure-isolation-pattern).

## Dashboard + MCP

| Symptom | Fix |
|---|---|
| Dashboard at `http://localhost:4040` empty | `stove` CLI running? `dashboard { }` registered in `Stove().with`? `appName` set? |
| `gRPC disabled` warning in logs | CLI started after tests; start the CLI before the test suite |
| Agent can't connect to MCP | Endpoint is on the CLI, not the test JVM. Verify `http://localhost:4040/api/v1/meta` returns `"mcp": { "enabled": true }` |
| `stove_trace` returns nothing | [Tracing](Components/15-tracing.md) not enabled |
| Disk filling with old run data | Run `stove --clear` to wipe stored runs from `~/.stove-dashboard.db` |

MCP is optional. It gives agents a structured way to read the same failure evidence humans see in the console and
dashboard.

## Migrating versions

| Bump | Notable change | Notes |
|---|---|---|
| `0.14 → 0.15` | `StoveSerde` replaces direct `ObjectMapper` | [Release notes](release-notes/0.15.0.md) |
| `0.21 → 0.21.2` | `configureStoveTracing` → `stoveTracing` (buildSrc); new Gradle plugin available | [Release notes](release-notes/0.21.2.md) |
| `0.21.2 → 0.22` | Mordant console reporting; `stove-quarkus` module | No breaking changes |
| `0.22 → 0.23` | Dashboard launched | Opt-in, non-blocking |
| `0.23 → 0.24` | Polyglot leap (provided app, keyed systems, process/container, Go, MCP) | All additive |

Pin the BOM, all `com.trendyol:stove-*` dependencies, the tracing Gradle plugin, and `stove-cli` to one Stove version.
Mixed versions are a common cause of class-load errors and empty dashboard data.

## Common FAQ

??? question "Can I use Stove with Java?"
    Yes for the AUT. Stove's test DSL itself is Kotlin-only. Java apps get tested by Kotlin test files.

??? question "Can I use JUnit instead of Kotest?"
    Yes. See [Getting Started](getting-started.md) for both setups.

??? question "How do I debug tests?"
    Set breakpoints in app code, run tests in debug mode, enable verbose logging via `logging.level.root=debug`, and use `using<T> { ... }` to inspect bean state.

??? question "Can I run tests in parallel?"
    Yes, with unique test data (UUIDs) and no shared state. For shared infra, use the [isolation pattern](#shared-infrastructure).

??? question "How do I test with TLS/SSL?"
    Configure the system with security on (e.g. `ElasticContainerOptions(disableSecurity = false)`) and read `cfg.certificate` in `configureExposedConfiguration`.

??? question "Can I use custom container images / registries?"
    Yes. Per-system `container = ...ContainerOptions(registry = "...", image = "...", tag = "...")` or globally via `DEFAULT_REGISTRY = "..."`.

??? question "Can I access the underlying Testcontainer?"
    `pause()` / `unpause()` from inside `stove { }`; `client()` for the underlying client (Lettuce, ES client, etc.); `containerFn = { c -> ... }` for one-off `GenericContainer` tweaks.

??? question "How do I handle DB migrations?"
    `postgresql { PostgresqlOptions(...).migrations { register<MyMigration>() } }`. Same shape across all SQL/NoSQL systems.

??? question "How do I test multiple databases / Kafka clusters?"
    Use [keyed systems](Components/20-multiple-systems.md). `postgresql(AppDb) { ... }` plus `postgresql(AnalyticsDb) { ... }`.

??? question "Can I share test setup across modules?"
    Yes. Extract `StoveConfig` and the test base class into a shared `test-extensions` module.

## Still stuck?

1. [GitHub Issues](https://github.com/Trendyol/stove/issues). Search first, then file
2. [Examples](https://github.com/Trendyol/stove/tree/main/examples) and [Recipes](recipes/index.md)
3. New issue. Include Stove version, JDK version, Docker version, full error, minimal repro

## Debug checklist

- Docker running and reachable (or [Provided Instances](Components/11-provided-instances.md) wired)
- One pinned Stove version across BOM + every dep
- App's reusable `run(args)` entrypoint extracted and exposed
- Test config injected via `withParameters` / `configureExposedConfiguration`
- Serializers aligned (Stove serde === app `ObjectMapper`)
- Kafka interceptor registered as bean + `kafka.interceptorClasses` property mapped
- External URLs in app are properties, not hardcoded
- Per-run prefixes on shared infra
- Containers have enough memory
- Ports free or `port = 0` for mocks
- Test extension registered (`StoveKotestExtension()` or `@ExtendWith(StoveJUnitExtension)`)
- For tracing: `stoveTracing` Gradle plugin applied + `tracing { enableSpanReceiver() }` in setup
