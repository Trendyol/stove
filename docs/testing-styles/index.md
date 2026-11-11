# Testing Styles

Choose how to express and discover your tests. Stove's system DSL works with
Kotest specs, JUnit Jupiter tests, and Cucumber feature files. Your application
runner is a separate choice: the same style can test Spring Boot, Ktor, Micronaut,
Quarkus, a process or container, or an already-running application.

## Choose a style

| Style | Test cases live in | Choose it when | Discovery |
|---|---|---|---|
| [Kotest](kotest.md) | Kotlin specs such as `FunSpec`, `ShouldSpec`, or `BehaviorSpec` | You want coroutine-native Kotlin tests with a choice of DSLs | Kotest engine on JUnit Platform |
| [JUnit Jupiter](junit.md) | Kotlin methods annotated with `@Test` | Your suite uses Jupiter lifecycle, extensions, and tooling | Jupiter engine on JUnit Platform |
| [Cucumber](cucumber.md) (incubating) | Gherkin `.feature` files bound to Kotlin suspend steps | You want feature scenarios and outline data to define the cases | Cucumber engine, launched by a JUnit Platform suite in the example |

Kotest's `BehaviorSpec` expresses Given/When/Then structure in Kotlin. Cucumber
expresses scenarios in feature files. Both can call the same suspend helpers and
Stove DSL; choose based on where you want to maintain your cases.

Using Kotest assertions such as `shouldBe` does not require running tests with the
Kotest engine. The Cucumber example uses those assertions while Cucumber executes
the scenarios. Likewise, running on JUnit Platform does not make Cucumber or Kotest
tests Jupiter tests.

## Shared application setup

All three styles follow the same lifecycle:

1. Configure systems and an application runner with `Stove().with { ... }`.
2. Call `run()` from the selected framework's startup lifecycle.
3. Use `stove { ... }` in tests, steps, and shared suspend helpers.
4. Call `Stove.stop()` from the corresponding teardown lifecycle.

The reporting adapter does not start the application. Application setup remains
specific to your suite; choose it from [JVM Frameworks](../frameworks/index.md),
[Polyglot](../other-languages/index.md), or
[Provided Application](../Components/19-provided-application.md).

For a small comparison, the Kotest and Jupiter pages use this shared helper in
`src/test/kotlin/example/e2e/StoveSetup.kt`. It expects an application that is
**already running** at `http://localhost:8080` with a `/hello` endpoint returning
`Hello, World!`:

```kotlin
package example.e2e

import com.trendyol.stove.http.HttpClientSystemOptions
import com.trendyol.stove.http.httpClient
import com.trendyol.stove.system.Stove
import com.trendyol.stove.system.providedApplication

suspend fun startStove() {
  Stove().with {
    httpClient { HttpClientSystemOptions(baseUrl = "http://localhost:8080") }
    providedApplication()
  }.run()
}
```

Replace this helper's configuration with your real systems and runner when Stove
should launch the application. The [Cucumber guide](cucumber.md) includes a complete
local Ktor example that starts its own application.

## Reporting and isolation

| Style | Register | Application lifecycle in these guides | Failure output |
|---|---|---|---|
| Kotest | `StoveKotestExtension()` | Project `beforeProject` / `afterProject` | Enriched test failure or error |
| Jupiter | `@ExtendWith(StoveJUnitExtension::class)` | Class `@BeforeAll` / `@AfterAll` | Enriched test failure |
| Cucumber | `StoveCucumberPlugin` and diagnostic glue | Native Cucumber `@BeforeAll` / `@AfterAll` | Scenario diagnostic attachment |

The adapters share the reporting lifecycle through `StoveTestSession`. Cucumber's
suspend backend also binds the scenario's Stove and trace context during invocation.
See [Reporting](../Components/13-reporting.md) to configure failure output.

Tests in one suite share the application and systems. Use unique data or explicit
per-test cleanup where needed. The Cucumber example runs scenarios in parallel,
with a separate report and trace session for each scenario. The application instance
is shared.
