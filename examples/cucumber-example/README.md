# Cucumber + Stove: reserve inventory

A Ktor inventory application tested through Gherkin features and Kotlin suspend
lambda steps with an implicit Stove validation receiver. It runs locally without Docker, with dashboard reporting, tracing, and profiling
enabled by default. Start `stove` in another terminal to view the collected evidence.

From the repository root:

```shell
./gradlew :examples:cucumber-example:test
```

This executes the four rows in `src/test/resources/features/inventory.feature`
in parallel, using up to four Cucumber workers. Steps within each row remain sequential.
Cucumber owns discovery, outlines, tags, hooks, and scenario-scoped injection.
The JUnit Platform suite selects the Cucumber engine; Kotest supplies assertions.
Gradle selects only the suite engine at the top level to avoid running features twice.
In an IDE, run the Gradle test task so the tracing and profiling agents are attached.

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

## Dashboard, tracing, and profiling

Start `stove` in another terminal, then run the tests:

```shell
./gradlew :examples:cucumber-example:test --rerun
```

Open <http://localhost:4040> and select `cucumber-example`. The example sends events
over gRPC to `localhost:4041`. Each outline row has its own scenario and HTTP timeline.
Select an HTTP event to inspect its result, error, and metadata.

The Gradle test task attaches the OpenTelemetry and Pyroscope agents and enables:

- **Trace:** HTTP client spans and Ktor server spans for PUT, POST, and GET,
  correlated with each scenario, including request/response metadata.
- **Drill Down:** CPU, allocation, live allocation, and lock/contention profiles
  for the test JVM. Profiles include the in-process application and test harness.
- **Span-to-profile correlation:** server spans carry `pyroscope.profile.id` so
  the dashboard can filter samples associated with that span.

A shared `@After` hook waits up to 15 seconds for the three requests' six spans before scenario
cleanup and asserts the server routes for successful scenarios. Failed scenarios
still wait for export, preserving their original failure. This matters for this
small application: scenario execution can finish before asynchronous export, especially on busy CI workers.
An export timeout reports the collected span names and IDs; the six-span requirement remains unchanged.
Expected HTTP 400/409 responses may appear as error **client spans** even though
the scenario passes, because the feature explicitly expects those responses.

Profiles are sampled: short requests may have no matching CPU samples, and live
allocation or contention data depends on GC and workload. Use the whole-run view
in Drill Down to explore the available samples.

To see passing scenarios alongside an intentional failure:

```shell
./gradlew :examples:cucumber-example:test -Pcucumber.tags='@reservation or @failure-demo'
```

The dashboard shows four passed scenarios and one failed scenario, with the failed
GET reporting `expected:<999> but was:<7>`. This command intentionally fails.
Observability is enabled on every run. Use the Gradle task from the terminal or
IDE: a native IDE suite run without the agents will fail the tracing checks.

## How it fits together

- `InventoryApplication.kt`: the real HTTP API, with atomic stock reservations.
- `InventoryEnvironment.kt`: one Stove start/stop pair per Cucumber run.
- `InventoryScenario`: one fresh state object and unique SKU per scenario/outline row.
- `InventorySteps.kt`: thin bindings from Gherkin to the existing Stove HTTP DSL.
- `InventorySteps` extends `StoveSteps` and registers `Given`/`When`/`Then`/`And`
  suspend steps in its constructor lambda. Each body calls `http { ... }` directly. Cucumber's
  Java8 backend discovers the glue; Stove executes each body inside the scenario
  session, carrying Stove, reporting, and tracing across suspensions. Reuse operations
  as `suspend fun ValidationDsl...` helpers. Lifecycle hooks keep native Cucumber imports.
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
lambda steps, the annotated alternative, shared helpers, and troubleshooting.
The [side-by-side comparison](../../docs/testing-styles/cucumber.md#the-same-scenario-lambdas-and-annotations)
shows the same feature implemented both ways and explains how lambda steps embed the Stove DSL.
