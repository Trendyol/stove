# Stove Cucumber extension (incubating)

Write Cucumber feature files and bind them to Kotlin suspend steps using the
standard `stove { ... }` DSL. Cucumber owns discovery, argument conversion, and
scenario execution. Stove supplies the coroutine context and scenario reporting.

This integration is incubating in 0.28.0 and is verified with Cucumber 8.0.4 and
JUnit Platform 6.1.3. For dependencies, application setup, and a complete feature,
follow the [Cucumber guide](../../docs/testing-styles/cucumber.md) or run the
[inventory example](../../examples/cucumber-example):

```shell
./gradlew :examples:cucumber-example:test
```

## Configure the suite

Include your step package and Stove's diagnostic hooks in the test resources'
`junit-platform.properties`:

```properties
cucumber.glue=your.steps.package,com.trendyol.stove.extensions.cucumber
cucumber.glue.hint.enabled=false
cucumber.plugin=com.trendyol.stove.extensions.cucumber.StoveCucumberPlugin
cucumber.execution.parallel.enabled=true
cucumber.execution.parallel.config.strategy=fixed
cucumber.execution.parallel.config.fixed.parallelism=4
cucumber.execution.parallel.config.fixed.max-pool-size=4
```

Disable glue hints with Cucumber 8.0.4: its optional performance advisor can throw
`IncompatibleClassChangeError` while inspecting Kotlin inline-coroutine classes
when scanning exceeds its timing threshold. This setting disables advice only;
step discovery and execution are unchanged.

Start Stove once in a native `io.cucumber.java.BeforeAll` hook and stop it in
`AfterAll`. Hooks are ordinary functions; use `runBlocking` around suspending
startup code. The backend is discovered automatically. The plugin and diagnostic
hooks manage reporting; application startup belongs to your suite.

## Write suspend steps

Steps must be public suspend **member functions**:

```kotlin
import com.trendyol.stove.extensions.cucumber.Then
import com.trendyol.stove.http.http
import com.trendyol.stove.system.stove
import io.kotest.matchers.shouldBe

class InventorySteps {
  @Then("product {word} has {int} units")
  suspend fun remaining(sku: String, quantity: Int) = stove {
    http {
      get<String>("/inventory/$sku") { it shouldBe quantity.toString() }
    }
  }
}
```

Import `Given`, `When`, `Then`, `And`, or `But` from
`com.trendyol.stove.extensions.cucumber`. These annotations all alias `Step`.
Native `io.cucumber.java.en.*` annotations are for synchronous steps; their Java
backend treats a suspend function's `Continuation` as an extra argument.

Extract shared operations into ordinary suspend helpers using `stove { ... }`.
The backend awaits each step and its structured child coroutines, carrying the
Stove instance, reporting identity, and trace context across dispatcher changes.
Exceptions and cancellation propagate to Cucumber. Top-level, static, inherited,
and extension-receiver step definitions are unsupported; top-level helpers are fine.

Cucumber's object factory provides scenario-scoped step instances. Native
synchronous steps, hooks, parameter types, data tables (including `@Transpose`),
and docstrings continue to use Cucumber's conversion and lifecycle APIs.

## Parallel execution and reporting

The configuration above and the example enable up to four concurrent scenarios.
Scenarios from the same or different feature files can overlap, and each Scenario
Outline row is a separate scenario. Steps within one scenario remain sequential.
Set `cucumber.execution.parallel.enabled=false` to run sequentially. When the
property is absent, Cucumber's own sequential default applies.

Each scenario has its own report and trace context, active from before its hooks
until after they finish. Context is cleared before the worker is reused. The
application and systems are shared across the suite, so use scenario-scoped state
and unique data; system cleanup runs at suite shutdown.

Failed scenarios receive a plain-text **Stove diagnostics** attachment. Scenario
IDs use the feature URI and scenario/example line, keeping outline rows distinct.
A hook failure after attachment is printed to stderr before cleanup. Undefined,
pending, and ambiguous steps are also reported as failures. Discovery and run-level
hook failures occur outside a scenario and have no scenario attachment.

For HTML/JSON reports with Cucumber 8, supply a supported JSON provider. The example
uses Jackson 2 with `jackson-datatype-jdk8`.

The adapter reuses `stove-extensions-common` for the reporting lifecycle. Its
real-engine tests cover both execution modes, including overlapping scenarios,
coroutine propagation, failure attachments, and cleanup on reused workers:

```shell
./gradlew :test-extensions:stove-extensions-cucumber:test
```
