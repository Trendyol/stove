# Quarkus Bridge integration tests

This fixture tests `com.trendyol.stove.quarkus.bridge()` and Stove's `using<T>` DSL against real Quarkus ArC beans.
It uses the repository's Quarkus version and needs no Docker services.
The Bridge implementation is provided by `stove-quarkus`; there is no test-local resolver.

## Run

From the repository root:

```bash
./gradlew :starters:quarkus:tests:quarkus-bridge:test
```

The fixture builds an augmented uber-jar with the `e2e` profile and disables
unused CDI bean removal at **build time** through the profile's default
`quarkus.arc.remove-unused-beans=false` setting. No Gradle flag is needed.
The Gradle test classpath places that
jar first and excludes raw main output. Stove's existing direct-main runner
then starts Quarkus in the test JVM, with application beans, their generated
proxies, ArC, and the tests sharing class identity.

The tests check that:

- A concrete `CounterService` resolved through `using<CounterService>` can mutate
  state that an HTTP request observes. A subsequent HTTP mutation is visible
  through Bridge, with the same instance identifier.
- A domain `CounterSnapshot` can cross between application code and test code
  as its actual type, without reflection adapters or DTO copying.
- `TestOnlyService`, which has no application injection points, observers,
  `@Named`, or `@Unremovable`, remains resolvable.
- Quarkus's transformed service class is loaded from the augmented jar. In
  particular, ArC's generated no-argument constructor exists even though the
  Java source declares only an injected constructor.
- ArC shuts down when Stove stops.
- Generic bean resolution preserves type arguments.
- Dependent beans have distinct instances and are destroyed when Stove stops.
- Missing or ambiguous beans produce actionable errors, and use after shutdown is rejected.

## Scope

This proves an in-process JVM Bridge is possible with an augmented, shared test
classpath. It does **not** prove that typed bean access works with Stove's current
fast-jar launcher: that launcher creates a separate `RunnerClassLoader`, so an
application bean and its test-side class can have different identities.

Use the build/classpath setup in this module with the public `bridge()` API.
Lookup uses default CDI qualifiers. Request scopes must already be active;
Bridge does not activate scopes or replace beans. Interceptors, repeated startup,
and the broader dependency compatibility matrix are outside this fixture's coverage.
Native executables cannot use this in-process Bridge.

All packaging and bean-preservation changes are confined to this e2e fixture.

## Verified results

The fixture runs with the repository's Quarkus version and bean preservation enabled by default.
