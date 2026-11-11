# Cucumber feature tests with suspend steps

Use Gherkin feature files to describe cases and Kotlin suspend functions to execute
Stove's existing validation DSL. The Cucumber adapter carries the active Stove
instance, scenario identity, and trace through suspensions and child coroutines.

!!! info "Incubating in 0.28.0"
    The adapter is incubating in the 0.28.0 source tree. Start with the local example
    and check module availability before using a published Stove version. The example
    is verified with Cucumber **8.0.4** and JUnit Platform **6.1.3**.

## Run the example

The [inventory example](https://github.com/Trendyol/stove/tree/main/examples/cucumber-example)
starts a real Ktor HTTP application with in-memory stock. It needs no Docker or
external application services. Observability is enabled by default; start `stove`
to view the dashboard. From a Stove checkout:

```shell
./gradlew :examples:cucumber-example:test
```

Four Scenario Outline rows exercise successful reservations, insufficient stock,
reserving all stock, and invalid quantities. They run in parallel with up to four
workers; the intentional failure demo is excluded by default. Feature files define
the cases, and Kotlin steps provide reusable operations.

## Dependencies and discovery

The example uses local Stove projects and the repository's version catalog:

```kotlin
--8<-- "examples/cucumber-example/build.gradle.kts"
```

When adapting this to another project, use a Stove BOM version that includes
`stove-extensions-cucumber`, along with the runner and systems your application
needs. The adapter supplies its backend and Kotlin reflection dependency. The
additional test dependencies serve these roles:

| Dependency | Purpose |
|---|---|
| `io.cucumber:cucumber-junit-platform-engine` | Feature discovery and execution |
| `org.junit.platform:junit-platform-suite` | Launch the Cucumber suite from Gradle or an IDE |
| `io.cucumber:cucumber-picocontainer` | Scenario-scoped constructor injection in this example |
| `io.kotest:kotest-assertions-core` | Assertions such as `shouldBe`; supplied by this repository's test conventions |
| `com.fasterxml.jackson.datatype:jackson-datatype-jdk8` | Supports the example's Jackson 2 runtime for Cucumber HTML/JSON reports |

Keep Cucumber artifacts aligned, and use a supported JSON provider for Cucumber 8.
Reuse an existing Cucumber object factory if your project already has one.

The suite selects feature resources in `src/test/resources/features`:

```kotlin
--8<-- "examples/cucumber-example/src/test/kotlin/stove/cucumber/example/CucumberSuite.kt"
```

The Gradle task selects only `junit-platform-suite` at the top level; the suite
selects the Cucumber engine. This avoids discovering the same features twice.
If this task also runs Kotest or Jupiter, include their engines alongside
`junit-platform-suite`, keeping `cucumber` out of top-level discovery. Separate
tasks are also supported. For a custom source set, configure the actual `Test`
task's classes, resources, and runtime classpath.

Add `junit-platform.properties` to the test resources:

```properties
--8<-- "examples/cucumber-example/src/test/resources/junit-platform.properties"
```

`cucumber.glue` includes both your steps and Stove's lifecycle hooks. The plugin
manages scenario reporting; the backend is discovered automatically through Java's
service loader. Update the glue package for your application. The `cucumber.tags`
Gradle property in the example overrides `cucumber.filter.tags`; the test resources
provide the default when launching the suite directly in an IDE.

Keep `cucumber.glue.hint.enabled=false` with Cucumber 8.0.4. Its optional
glue-loading performance advisor can throw `IncompatibleClassChangeError` when
inspecting Kotlin inline-coroutine classes. The advisor runs only after a timing
threshold, so this may appear only on slower CI workers. Disabling hints leaves
step discovery, execution, and reporting unchanged.

## Start Stove once per run

Use native Cucumber run-level hooks to start and stop Stove. These hooks remain
ordinary functions; startup uses `runBlocking` because `Stove.run()` suspends.

```kotlin
--8<-- "examples/cucumber-example/src/test/kotlin/stove/cucumber/example/InventoryEnvironment.kt"
```

`startInventoryApplication` is the example's
[Ktor application](https://github.com/Trendyol/stove/blob/main/examples/cucumber-example/src/main/kotlin/stove/cucumber/example/InventoryApplication.kt).
It starts with `wait = false`. For another application, use its existing Stove
runner and systems. Keep startup out of individual steps.

## Feature files and suspend steps

```gherkin
--8<-- "examples/cucumber-example/src/test/resources/features/inventory.feature"
```

Steps import `Given`, `When`, and `Then` from
**`com.trendyol.stove.extensions.cucumber`** and use the regular `stove {}` DSL:

```kotlin
--8<-- "examples/cucumber-example/src/test/kotlin/stove/cucumber/example/InventorySteps.kt"
```

Stove's `Given`, `When`, `Then`, `And`, and `But` are aliases for the same `Step`
annotation. Their keyword spelling does not constrain which Gherkin keyword can
match a step. Define annotated steps as public suspend members of a class.
Top-level, static, inherited, and extension-receiver step definitions are unsupported.
Shared suspend helpers can be top-level functions.

PicoContainer creates a fresh `InventoryScenario` and `InventorySteps` for each
scenario, including each outline row. The scenario owns its unique SKU and response
state; parallel scenarios share the application without sharing those values.
Stove system cleanup runs at suite shutdown, not between scenarios. Per-scenario
cleanup should target only the data that scenario owns.

### Reuse suspend helpers

A helper can use the same DSL without taking a Stove instance or repeating setup:

```kotlin
import com.trendyol.stove.http.http
import com.trendyol.stove.system.stove
import io.kotest.matchers.shouldBe

suspend fun assertStock(sku: String, expected: Int) = stove {
  http {
    get<String>("/inventory/$sku") { it shouldBe expected.toString() }
  }
}
```

Call it from a suspend step. The session's `StoveContext`, reporting context, and
trace propagate through suspensions and structured child coroutines, including
`withContext(Dispatchers.Default)`. The backend awaits the step and its structured
children before returning to Cucumber. Exceptions and cancellation remain scenario
failures. Detached coroutine scopes do not inherit this lifecycle automatically.

### Why Stove annotations?

Cucumber's Java backend treats Kotlin's synthetic `Continuation` as another
argument. Importing `io.cucumber.java.en.Then` on a suspend function therefore
produces an argument-count mismatch. Stove's annotations select its suspend-aware
backend. JUnit Platform launches the Cucumber engine; Jupiter is a separate engine,
so its coroutine support does not handle Cucumber steps.

Stove's backend handles only its own annotation, so the Java backend can coexist
without duplicate registrations. Native synchronous steps, hooks, parameter types,
and converters retain their `io.cucumber.java.*` imports. Cucumber still handles
expression matching, custom parameter types, data tables (including `@Transpose`),
and docstrings.

## Parallel scenarios

The example enables `cucumber.execution.parallel.enabled=true`, with both fixed
parallelism and maximum pool size set to four. Cucumber can run scenarios from the same or different feature
files concurrently; each Scenario Outline row is a separate scenario. Steps within
one scenario remain ordered, and each suspend step finishes before the next starts.

Each scenario has its own reporting and trace session. Context follows structured
child coroutines and dispatcher changes, and the session is cleared before its
Cucumber worker is reused. The application and its systems are shared across the
suite, so keep scenario data independent, as the example does with unique SKUs and
scenario-scoped injection.

Stove accepts both parallel and sequential execution. Cucumber itself defaults to
sequential execution when the property is absent; Stove does not override engine
configuration. Set `cucumber.execution.parallel.enabled=false` when a suite needs
sequential scenarios.

## Tags and failure reports

Select scenarios with the example's Gradle property:

```shell
./gradlew :examples:cucumber-example:test -Pcucumber.tags='@reservation'
```

To see diagnostics, run the deliberate failure:

```shell
./gradlew :examples:cucumber-example:test -Pcucumber.tags='@failure-demo'
```

This command is expected to fail: it asserts 999 remaining units when the actual
stock is 7. Open `examples/cucumber-example/build/reports/cucumber.html`, expand
the failed scenario's hooks, then **Stove diagnostics**. JSON is also written to
`build/reports/cucumber.json` inside the example. The Gradle property works because
the example forwards it to `cucumber.filter.tags`; configure that forwarding when
adapting the task.

Each executed scenario gets its own `StoveTestSession`, opened by Stove's
`@Before(order = Int.MIN_VALUE)` hook and closed after all scenario hooks. IDs use
the feature URI plus scenario/example line, keeping identical outline names distinct.
System activity uses the same reporting lifecycle as Stove's Kotest and Jupiter adapters.

The diagnostic hook attaches plain text without replacing the original exception.
It runs at `Int.MIN_VALUE` to follow ordinary `@After` hooks. If a hook fails after
the attachment, the plugin prints the remaining diagnostics to stderr before
cleanup. Undefined, ambiguous, and pending steps are also reported as failures.
Discovery errors and run-level hook failures remain Cucumber run failures.

## Dashboard, tracing, and profiling

Start the Stove dashboard (`stove`), then run the example with:

```shell
./gradlew :examples:cucumber-example:test --rerun
```

Open <http://localhost:4040> and select `cucumber-example`. Every run enables
dashboard ingestion, OpenTelemetry instrumentation, and Pyroscope profiling.
Each scenario has an HTTP timeline and six client/server spans in **Trace**.
**Drill Down** shows CPU, allocation, surviving-object, and contention samples;
select a server span's **Drill Down · this span** action for correlated samples.
Short operations may have no samples. Whole-run profiles include both the
application and test harness in the shared JVM.

The example's shared after-hook waits for asynchronous span export before scenario
cleanup and verifies the server routes for successful scenarios. Expected HTTP
400/409 responses can mark client spans as errors while their scenarios pass.
Use the Gradle task from the terminal or IDE so both agents are attached.

## Troubleshooting and limits

| Symptom | Check |
|---|---|
| Extra `Continuation` argument in a step error | Use Stove's step annotation import for suspend functions |
| “No Stove Cucumber scenario” | Register `StoveCucumberPlugin` in `cucumber.plugin` |
| Stove is not initialized | Start it in a native `@BeforeAll` hook before scenarios |
| No diagnostic attachment | Include `com.trendyol.stove.extensions.cucumber` in glue; run-level failures have no scenario attachment |
| Features run twice | Keep `cucumber` out of top-level discovery when launching through the suite engine |
| Annotated step is rejected during discovery | Use a public suspend class member; extract reuse into helpers |
| State leaks between scenarios | Use scenario-scoped state, unique data, and cleanup limited to that scenario's data |
| Validate glue without starting the application | `cucumber.execution.dry-run=true` skips step bodies and hooks, including `BeforeAll` / `AfterAll`; no Stove instance or reporting session is needed |

Lifecycle hooks use native synchronous Cucumber annotations. Scenarios share one
Stove application lifecycle; `StoveContext` binds validation to that active instance.

See [Reporting](../Components/13-reporting.md) for report configuration and
[Tracing](../Components/15-tracing.md) for application trace instrumentation.
