# Quarkus

Stove starts the real Quarkus runtime. `bridge()` exposes CDI beans through `using<T>` when the augmented application shares the test classpath. The default fast-jar launcher supports verification through HTTP, DB queries, Kafka observations, gRPC, and other external systems.

<a class="open-in-wizard" data-fw="quarkus" data-sys="http,postgresql,kafka">Open Quarkus + Postgres + Kafka in wizard</a>

<div class="stove-tldr" markdown>
<span class="stove-tldr-title">Quarkus specifics</span>
1) Keep <code>@QuarkusMain</code> intact. 2) Startup is detected automatically, including apps without HTTP. 3) For Bridge, build an augmented uber-jar and use the shared test classpath described below.
</div>

## Setup

```kotlin
dependencies {
    testImplementation(platform("com.trendyol:stove-bom:$stoveVersion"))
    testImplementation("com.trendyol:stove")
    testImplementation("com.trendyol:stove-quarkus")
    testImplementation("com.trendyol:stove-extensions-kotest")  // or -junit

    testImplementation("com.trendyol:stove-http")
    testImplementation("com.trendyol:stove-postgres")
    testImplementation("com.trendyol:stove-kafka")
    testImplementation("com.trendyol:stove-wiremock")
    testImplementation("com.trendyol:stove-tracing")
}
```

Keep the normal Quarkus entrypoint:

```kotlin
@QuarkusMain
object QuarkusMainApp {
  @JvmStatic
  fun main(args: Array<String>) {
    Quarkus.run(*args)
  }
}
```

Stove waits for Quarkus to finish application startup before running tests. This works
with both the shared classpath and isolated fast-jar runtime, including applications
without HTTP. No application-side readiness bean or system property is needed.
Startup failures are reported to the test JVM, and the startup timeout remains
configurable through `stove.quarkus.startup.timeout.ms` (default: 120 seconds).

Minimal `Stove().with { }`:

```kotlin
Stove().with {
    tracing { enableSpanReceiver() }
    quarkus(
        runner = { params -> QuarkusMainApp.main(params) },
        withParameters = listOf("quarkus.http.port=8080")
    )
}.run()
```

## Bridge

Import `com.trendyol.stove.quarkus.bridge` and register it before the runner:

```kotlin
import com.trendyol.stove.quarkus.bridge
import com.trendyol.stove.quarkus.quarkus
import com.trendyol.stove.system.*

Stove().with {
    bridge()
    quarkus(
        runner = { params -> QuarkusMainApp.main(params) },
        withParameters = listOf("quarkus.http.port=8080")
    )
}.run()

stove {
    using<OrderService> { findOrder(orderId).status shouldBe "CREATED" }
}
```

Your application must include `io.quarkus:quarkus-arc`. In its `application.properties`,
preserve all discovered CDI beans in the e2e profile and build an augmented uber-jar:

```properties
%e2e.quarkus.package.jar.type=uber-jar
%e2e.quarkus.arc.remove-unused-beans=false
```

These are **build-time** settings: setting them only in Stove's `withParameters`
cannot restore beans removed from an already packaged application.

For a dedicated e2e module with the Quarkus Gradle plugin applied, use this build setup:

```kotlin
quarkus {
    quarkusBuildProperties.put("quarkus.profile", "e2e")
}

val augmentedJar = tasks.named<io.quarkus.gradle.tasks.QuarkusBuild>("quarkusBuild")
    .map { it.runnerJar }

tasks.test {
    dependsOn("quarkusBuild")
    classpath = files(augmentedJar) +
        (sourceSets.test.get().runtimeClasspath - sourceSets.main.get().output)
    systemProperty("quarkus.profile", "e2e")
    systemProperty("java.util.logging.manager", "org.jboss.logmanager.LogManager")
}
```

For another e2e source set, apply the classpath configuration to its actual `Test`
task and use that source set's runtime classpath. Keep the e2e profile selection
confined to e2e builds if the project also produces production artifacts.

Putting the augmented jar first and excluding raw main output keeps Quarkus's
transformed classes and generated proxies compatible with types used in tests.
The separate fast-jar `RunnerClassLoader` cannot provide this class identity;
Stove rejects Bridge with that launcher before starting the application.

Bridge supports unqualified CDI lookup, including parameterized types such as
`using<List<String>>`. Missing or ambiguous beans produce a lookup error.
Dependent instances are retained until Stove stops and are then destroyed before
the application shuts down. Request scopes must already be active; Bridge does
not activate them or replace beans. Native executables and external processes
cannot use this in-process Bridge.

See the [Quarkus Bridge integration tests](https://github.com/Trendyol/stove/tree/main/starters/quarkus/tests/quarkus-bridge)
for a complete build and shared-state assertions through both Bridge and HTTP.

## Kafka note

`stove-kafka` + Quarkus Kafka clients need shared classloading. Add to `application.properties`:

```properties
quarkus.class-loading.parent-first-artifacts=org.apache.kafka:kafka-clients
```

Without this, Stove's Kafka interceptor can't attach.

## What you get

- :white_check_mark: Real Quarkus startup via the normal `main`
- :white_check_mark: Tracing, reporting, and dashboard when configured; MCP when the `stove` server is running and dashboard data exists
- :white_check_mark: Bridge with the augmented uber-jar and shared test classpath

## Verification without Bridge

Instead of `using<OrderService> { ... }`, query state through the systems your app writes to:

```kotlin
test("order is created") {
  val id = UUID.randomUUID().toString()

  stove {
    http {
      post<OrderResponse>("/orders", CreateOrderRequest(id).some()) {
        it.status shouldBe 201
      }
    }

    postgresql {
      shouldQuery<OrderRow>(
        query = "SELECT id, status FROM orders WHERE id = '$id'",
        mapper = { row -> OrderRow(row.string("id"), row.string("status")) }
      ) {
        it.first().status shouldBe "CREATED"
      }
    }

    kafka {
      shouldBePublished<OrderCreated> { actual.id == id }
    }
  }
}
```

## Example

- [quarkus-example](https://github.com/Trendyol/stove/tree/main/examples/quarkus-example)
