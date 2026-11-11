# JUnit Jupiter

Use Jupiter when your suite is organized around `@Test` methods and Jupiter's
lifecycle and extension APIs. Stove's Jupiter extension starts a report for each
test and enriches failures with the recorded system activity.

The following example uses the [shared setup helper](index.md#shared-application-setup)
and a regular `src/test` source set. It demonstrates one class owning its application
lifecycle. For a larger suite, keep your existing shared startup and teardown owner;
do not start a separate Stove instance for every class running concurrently.

## Dependencies and discovery

Use a Stove BOM matching your project and an aligned JUnit BOM. This example targets
JUnit 6; the repository is verified with 6.1.3.

```kotlin
dependencies {
  testImplementation(platform("com.trendyol:stove-bom:$stoveVersion"))
  testImplementation(platform("org.junit:junit-bom:$junitVersion"))
  testImplementation("com.trendyol:stove-extensions-junit")
  testImplementation("com.trendyol:stove-http")
  testImplementation("org.junit.jupiter:junit-jupiter-api")
  testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine")
  testRuntimeOnly("org.junit.platform:junit-platform-launcher")
  testRuntimeOnly(kotlin("reflect"))
}

tasks.test {
  useJUnitPlatform { includeEngines("junit-jupiter") }
}
```

The engine filter assumes this task runs only Jupiter tests. Registering the Stove
extension does not supply the Jupiter engine at runtime. Add `junit-jupiter-params`
if your suite uses parameterized tests.

## Lifecycle and suspend tests

JUnit 6 supports Kotlin suspending test methods when Kotlin reflection and
`kotlinx-coroutines-core` are available; Stove's dependencies already bring in the
coroutines runtime. The following class belongs in the same `example.e2e` package
as the shared setup helper:

```kotlin
package example.e2e

import com.trendyol.stove.extensions.junit.StoveJUnitExtension
import com.trendyol.stove.http.http
import com.trendyol.stove.system.Stove
import com.trendyol.stove.system.stove
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith

@ExtendWith(StoveJUnitExtension::class)
class GreetingTest {
  companion object {
    @JvmStatic
    @BeforeAll
    fun start(): Unit = runBlocking { startStove() }

    @JvmStatic
    @AfterAll
    fun stop() = Stove.stop()
  }

  @Test
  suspend fun returnsGreeting() {
    stove {
      http {
        get<String>("/hello") { assertEquals("Hello, World!", it) }
      }
    }
  }
}
```

On an older Jupiter version without suspend-method support, use an ordinary
`@Test fun returnsGreeting(): Unit = runBlocking { ... }`. Keep shared helpers
suspending in either case.

Jupiter invokes these methods. Its coroutine support does not apply to a different
test engine: Cucumber needs its own [suspend-step backend and annotations](cucumber.md).

Run the class:

```shell
./gradlew test --tests 'example.e2e.GreetingTest'
```

Nested classes and display names are included in the report's test path. Repeated
and parameterized invocations receive separate report IDs, even when their display
names match. See
[Reporting](../Components/13-reporting.md) for output configuration and
[Getting Started](../getting-started.md) for application runner setup.
