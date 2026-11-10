# Quarkus Recipe Setup

This recipe uses Stove `1.0.0.572-SNAPSHOT` with HTTP, tracing, and the Quarkus CDI Bridge.
Run it from `recipes/jvm`:

```shell
./gradlew :java-recipes:quarkus-basic-recipe:e2eTest
```

`StoveConfig` registers `bridge()` and launches the normal `QuarkusMainApp.main`.
Stove automatically detects Quarkus startup and shuts it down in `afterProject`;
no custom startup observer, system-property signal, or readiness endpoint is needed.

## Inventory flow

`IndexTests` seeds an `Item` through `using<ItemRepository>`, reads it through
`GET /items/{id}`, updates it through `PUT /items/{id}`, and checks the typed
`Item` through Bridge again. Both paths use the same application-scoped repository.
Unique item IDs keep tests independent. The repository resets when the app stops.

`using<InventorySummary>` resolves a CDI bean that no HTTP resource injects,
showing that beans used only by tests survive e2e augmentation. Existing greeting
and tracing checks exercise the real HTTP application.

## Augmented classpath

This dedicated recipe builds with the `e2e` profile, which selects an uber-jar and
sets `quarkus.arc.remove-unused-beans=false` at build time. The `e2eTest` task
builds that jar and places it before test dependencies, excluding raw main output
from the runtime classpath. Test compilation still sees main output. This lets
Bridge and Quarkus share augmented CDI classes and typed application objects.

Keep production packaging separate when adapting this setup to a production
module. The isolated fast-jar launcher cannot expose beans through Bridge.
See the [Quarkus Bridge guide](https://trendyol.github.io/stove/frameworks/quarkus/#bridge).

## Tracing

The Stove tracing plugin attaches the OpenTelemetry agent to `e2eTest`. The tracing
test checks that a real `/hello` request emits spans.
