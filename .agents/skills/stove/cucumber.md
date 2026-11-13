# Cucumber feature tests

Use this guide when adding or debugging feature files, Kotlin suspend steps, or
the Stove Cucumber backend. Preserve the user's chosen application runner and
existing Cucumber object factory. This adapter is published in 0.28.0 and remains incubating; first verify the
resolved Stove version contains `stove-extensions-cucumber`.
Do not infer availability from newer docs or the skill installation.

## Execution model

Cucumber owns discovery, Gherkin, outlines, tags, argument conversion, and scenario
execution. JUnit Platform launches its engine. Jupiter coroutine support does not
apply to Cucumber glue; no Jupiter extension or Kotest execution bridge is needed.
Kotest assertions may still be used.

Cucumber’s Java8 backend discovers `StoveSteps` lambda glue. Stove’s annotated
backend is also loaded automatically beside the native Java backend. Both styles
share one suspend executor and reporting lifecycle. `StoveCucumberPlugin` manages the shared `StoveTestSession`.
Stove's first before hook opens it, and its after hook attaches scenario failures.
Keep these responsibilities in the adapter; do not implement per-step reporting,
feature parsing, or copied test cases in application glue.

## Wire the suite

- Add `stove-extensions-cucumber`, the application's Stove runner, and required
  systems, aligned through its BOM when using published artifacts. In this checkout,
  use the local project dependency as `examples/cucumber-example` does.
- Add aligned Cucumber engine and object-factory dependencies plus JUnit Platform
  suite support. The example is tested with Cucumber 8.0.4 and Platform 6.1.3. Read
  the resolved versions before changing an existing project. For HTML/JSON reports,
  Cucumber 8 needs a supported JSON provider; this repo uses Jackson 2 plus its
  `jackson-datatype-jdk8` module.
- Put features in the test resource directory. The example's suite uses `@Suite`,
  `@IncludeEngines("cucumber")`, and `@SelectPackages("features")`.
- With that suite arrangement, use `includeEngines("junit-platform-suite")` on the
  actual Gradle `Test` task to avoid duplicate discovery. In a shared task, include
  Kotest or Jupiter alongside the suite engine and keep `cucumber` out of top-level
  discovery. Separate tasks are also supported; see [gradle-config.md](gradle-config.md).
- In test-resource `junit-platform.properties`, set:

```properties
cucumber.glue=your.steps.package,com.trendyol.stove.extensions.cucumber
cucumber.glue.hint.enabled=false
cucumber.plugin=com.trendyol.stove.extensions.cucumber.StoveCucumberPlugin
cucumber.execution.parallel.enabled=true
cucumber.execution.parallel.config.strategy=fixed
cucumber.execution.parallel.config.fixed.parallelism=4
cucumber.execution.parallel.config.fixed.max-pool-size=4
```

Append `pretty`, `html:...`, or `json:...` plugins when reports are wanted. Register
this plugin once. The backend needs no additional manual registration. The example
runs scenarios and outline rows in parallel by default; steps within each scenario
remain sequential. Stove also supports `cucumber.execution.parallel.enabled=false`
and respects the engine configuration. Cucumber itself remains sequential when the property is absent.

Keep glue hints disabled with Cucumber 8.0.4. Its optional performance advisor can
throw `IncompatibleClassChangeError` while inspecting Kotlin inline-coroutine
classes once scanning exceeds its timing threshold, often only in CI. This
setting disables advice, not discovery or execution.

Start Stove once using native `io.cucumber.java.BeforeAll` and stop with native
`AfterAll`. An `object` with `@JvmStatic` hooks works. Startup is an ordinary
`fun start(): Unit = runBlocking { Stove().with { /* systems and runner */ }.run() }`;
teardown can call `Stove.stop()`. Neither the backend nor reporting plugin starts
an application. Hooks are synchronous; suspend support is for step bodies.

## Write steps and reuse the DSL

```kotlin
import com.trendyol.stove.extensions.cucumber.StoveSteps
import com.trendyol.stove.http.http
import io.kotest.matchers.shouldBe

class InventorySteps : StoveSteps({
  Then<String, Int>("product {word} has {int} units") { sku, quantity ->
    http {
      get<String>("/inventory/$sku") { it shouldBe quantity.toString() }
    }
  }
})
```

- Prefer `StoveSteps` for new lambda glue. Register `Given`, `When`, `Then`, `And`,
  and `But` inside `StoveSteps({ ... })`; their suspend bodies have a `ValidationDsl` receiver. System
  DSLs are directly available. Extract reuse as `suspend fun ValidationDsl.helper(...)`;
  those helpers also work inside `stove {}` in other testing styles.
- Capture injected constructor parameters or declare local scenario state in the
  registration lambda. It runs before subclass properties are initialized. The
  `StoveSteps() { init { ... } }` form remains supported.
- Zero to nine typed arguments are supported. Use explicit type arguments or typed
  lambda parameters. Custom native `@ParameterType` conversion remains available.
- Lambda tables use `DataTable`, with explicit `asList`/`asLists`/`asMap`/`asMaps` or
  `convert`. Generic `List`/`Map` element types are erased by the lambda backend. Use
  `table.transpose()` for transposed tables; use `String` or `DocString` for docstrings.
- Keep the annotated style when desired: import step annotations from
  `com.trendyol.stove.extensions.cucumber`, declare public suspend member functions,
  and call `stove {}` inside them. This supports generic table conversion and
  `@Transpose`. Top-level, inherited, static, and extension-receiver annotated steps
  are unsupported. Native `io.cucumber.java.en.*` annotations treat `Continuation`
  as an extra argument on suspend functions.
- Both styles await each step and its structured children. Native synchronous steps
  can coexist. Keep hooks and parameter/table/docstring converters on native
  Cucumber imports; hooks remain synchronous. Do not register an expression twice.
- The session propagates `StoveContext`, reporting identity, and tracing through
  suspension and dispatcher changes. Concurrent scenarios have separate report and
  trace sessions while sharing the application. Detached work is outside that
  structured lifecycle.
- Put variable cases in Scenario Outlines. Use constructor-injected scenario state
  and unique test data; Stove's system cleanup runs at suite shutdown, not after
  each scenario. Per-scenario cleanup must target only that scenario's data. Reuse an
  existing object factory instead of adding a competing one.

## Diagnose and verify

Check failures against the actual task's Cucumber report. Scenario IDs contain the
feature URI and scenario/example line. `Stove diagnostics` is a plain-text attachment
on an after hook; exceptions retain their original cause. Pending, undefined, and
ambiguous steps count as failures. A later failing hook may leave diagnostics only
on stderr. Discovery and run-level hook failures have no scenario attachment.

For a continuation-argument error, check annotation imports first. For “No Stove
Cucumber scenario,” check plugin registration. For missing attachments, check Stove's
glue package. If concurrent scenarios interfere, check shared test data and cleanup.
If features run twice, inspect the suite and top-level engine selection before
changing feature selectors. Dry runs skip step bodies and all hooks, including
run-level startup and teardown. They need no Stove instance and open no reporting sessions.

In this checkout:

```shell
./gradlew :examples:cucumber-example:test
./gradlew :test-extensions:stove-extensions-cucumber:test
```

The example has four passing outline rows and an excluded failure demo. Running
`:examples:cucumber-example:test -Pcucumber.tags=@failure-demo` intentionally fails;
the example's task forwards this property to `cucumber.filter.tags`. Do not assume
that project property works in another build without equivalent forwarding.

When changing the backend or reporting lifecycle, retain real-engine coverage for
outlines/tags, scenario isolation, dispatcher propagation, conversions, failures,
cancellation, and diagnostic attachments in both execution modes. Parallel tests
should prove overlap, isolated diagnostics, and cleanup on reused workers.
Changes to `stove-extensions-common` also warrant Kotest and Jupiter adapter
regression tests.

## Source anchors

In a Stove checkout, inspect `examples/cucumber-example`,
`test-extensions/stove-extensions-cucumber`, and `test-extensions/stove-extensions-common`.
The walkthrough is [Cucumber feature tests](https://trendyol.github.io/stove/testing-styles/cucumber/).
Use sources at the matching release when these folders are absent downstream.
