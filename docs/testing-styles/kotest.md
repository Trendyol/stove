# Kotest

Kotest provides coroutine-native test bodies and several Kotlin DSLs. Stove's
extension works with leaf tests across those styles, keeping nested containers
such as `context` or `given` as part of the report's display path.

The following example uses the [shared setup helper](index.md#shared-application-setup)
and a regular `src/test` source set. For a dedicated E2E task, use its own classes,
resources, runtime classpath, and project configuration.

## Dependencies and discovery

Use versions from your project's catalog; the Stove BOM aligns Stove modules.
The current repository uses Kotest 6.2.6 with the JUnit 6 runner.

```kotlin
dependencies {
  testImplementation(platform("com.trendyol:stove-bom:$stoveVersion"))
  testImplementation("com.trendyol:stove-extensions-kotest")
  testImplementation("com.trendyol:stove-http")
  testImplementation("io.kotest:kotest-runner-junit6:$kotestVersion")
  testImplementation("io.kotest:kotest-assertions-core:$kotestVersion")
  testRuntimeOnly("org.junit.platform:junit-platform-launcher:$junitVersion")
}

tasks.test {
  useJUnitPlatform { includeEngines("kotest") }
}
```

The engine filter assumes this task runs only Kotest specs. Keep other suites on
their intended tasks or retain their engines in a shared task.

Create `src/test/kotlin/example/e2e/TestConfig.kt`:

```kotlin
package example.e2e

import com.trendyol.stove.extensions.kotest.StoveKotestExtension
import com.trendyol.stove.system.Stove
import io.kotest.core.config.AbstractProjectConfig
import io.kotest.core.extensions.Extension

class TestConfig : AbstractProjectConfig() {
  override val extensions: List<Extension> = listOf(StoveKotestExtension())

  override suspend fun beforeProject() = startStove()
  override suspend fun afterProject() = Stove.stop()
}
```

Kotest 6 does not automatically scan `AbstractProjectConfig`. Register the class in
`src/test/resources/kotest.properties`:

```properties
kotest.framework.config.fqn=example.e2e.TestConfig
```

## Write a spec

```kotlin
package example.e2e

import com.trendyol.stove.http.http
import com.trendyol.stove.system.stove
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class GreetingTest : FunSpec({
  context("greetings") {
    test("returns the greeting") {
      stove {
        http {
          get<String>("/hello") { it shouldBe "Hello, World!" }
        }
      }
    }
  }
})
```

The test body already suspends, so no `runBlocking` is needed. Extract reusable
operations into suspend functions using the same `stove {}` DSL. Use `ShouldSpec`,
`StringSpec`, or another Kotest style when it suits the suite; switching spec style
does not require another Stove adapter. Lifecycle and context propagation are
covered across `FunSpec`, `BehaviorSpec`, `StringSpec`, `DescribeSpec`, `ShouldSpec`,
`ExpectSpec`, `FeatureSpec`, `FreeSpec`, `WordSpec`, and `AnnotationSpec`.

Run the task and confirm it discovers tests:

```shell
./gradlew test --tests 'example.e2e.GreetingTest'
```

For feature files with reusable step bindings, see [Cucumber](cucumber.md). For
application startup configuration, continue with [Getting Started](../getting-started.md).
