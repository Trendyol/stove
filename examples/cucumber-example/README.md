# Cucumber + Stove: reserve inventory

A Ktor inventory application tested through Gherkin features and Kotlin suspend
steps using the standard `stove { ... }` DSL. It runs locally without Docker or
external services.

From the repository root:

```shell
./gradlew :examples:cucumber-example:test
```

This executes the four rows in `src/test/resources/features/inventory.feature`
in parallel, using up to four Cucumber workers. Steps within each row remain sequential.
Cucumber owns discovery, outlines, tags, hooks, and scenario-scoped injection.
The JUnit Platform suite selects the Cucumber engine; Kotest supplies assertions.
Gradle selects only the suite engine at the top level to avoid running features twice.
You can also run `CucumberSuite` in an IDE.

Glue-loading hints are disabled to avoid Cucumber 8.0.4's performance advisor
crashing on generated Kotlin inline-coroutine classes during slower scans.
The Gradle test task sets the hint threshold to zero so removing this workaround
is exercised even on fast machines. Discovery and parallel execution stay enabled.

To select scenarios by tag:

```shell
./gradlew :examples:cucumber-example:test -Pcucumber.tags='@reservation'
```

## See a failure

```shell
./gradlew :examples:cucumber-example:test -Pcucumber.tags='@failure-demo'
```

This command **intentionally fails**: the feature expects 999 units after reserving
3 of the available 10. Open `build/reports/cucumber.html` inside this example and
expand the failed scenario's `Stove diagnostics` attachment. It includes the HTTP
operations, expected/actual values, and the feature's location. The original
Cucumber failure is preserved. JSON is written to `build/reports/cucumber.json`.
The failure demo is excluded by default, including when running the IDE suite.

## View the dashboard

Start `stove` in another terminal, then enable dashboard ingestion:

```shell
./gradlew :examples:cucumber-example:test -Pdashboard --rerun
```

Open <http://localhost:4040> and select `cucumber-example`. The example sends events
over gRPC to `localhost:4041`. Each outline row has its own scenario and HTTP timeline.
Select an HTTP event to inspect its result, error, and metadata.

To see passing scenarios alongside an intentional failure:

```shell
./gradlew :examples:cucumber-example:test -Pdashboard -Pcucumber.tags='@reservation or @failure-demo'
```

The dashboard shows four passed scenarios and one failed scenario, with the failed
GET reporting `expected:<999> but was:<7>`. This command intentionally fails.
The example does not enable tracing instrumentation, so the Trace tab has no spans.
Dashboard ingestion is disabled by default; ordinary runs need no external server.
For an IDE run, set the VM option `-Dstove.dashboard.enabled=true`.

## How it fits together

- `InventoryApplication.kt`: the real HTTP API, with atomic stock reservations.
- `InventoryEnvironment.kt`: one Stove start/stop pair per Cucumber run.
- `InventoryScenario`: one fresh state object and unique SKU per scenario/outline row.
- `InventorySteps.kt`: thin bindings from Gherkin to the existing Stove HTTP DSL.
- Suspend steps use `Given`/`When`/`Then` from
  `com.trendyol.stove.extensions.cucumber` and the regular `stove { http { ... } }`
  DSL. The automatically discovered backend invokes each step inside the scenario
  session, carrying Stove, reporting, and tracing across suspensions. Shared suspend
  helpers inherit that context. Lifecycle hooks keep their native Cucumber imports.
- `junit-platform.properties`: glue, reporting, parallel worker limits, and default tags.

The example uses the local `stove-extensions-cucumber` project. Reporting lifecycle
code is shared with Stove's Kotest and Jupiter adapters through
`stove-extensions-common`. Feature files define the cases, and step implementations
are shared across scenarios.

## Scenario isolation and diagnostics

There is one report/trace session per scenario, including all its hooks, isolated
across concurrent scenarios. Set `cucumber.execution.parallel.enabled=false` in
`junit-platform.properties` if you need sequential execution. The application is
shared: each scenario uses a unique SKU, and system cleanup runs at suite shutdown.

Stove's diagnostic hook runs at `Int.MIN_VALUE`, after ordinary `@After` hooks.
A hook failure after attachment is printed to stderr before the session closes.

See the [Cucumber guide](../../docs/testing-styles/cucumber.md) for dependencies,
annotation imports, shared helpers, and troubleshooting.
