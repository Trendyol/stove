# Quarkus Bridge setup

## Contents

- [Check applicability](#check-applicability)
- [Build and load the augmented application](#build-and-load-the-augmented-application)
- [Register Bridge and access beans](#register-bridge-and-access-beans)
- [CDI behavior](#cdi-behavior)
- [Troubleshooting](#troubleshooting)
- [Verify the setup](#verify-the-setup)

## Check applicability

Quarkus Bridge exposes the running application's ArC CDI beans through Stove's
`using<T>` DSL. Use it with the in-process Quarkus runner and a shared, augmented
runtime classpath. Process, container, native executable, and provided-application
runners cannot expose application objects this way. The isolated Quarkus fast-jar
launcher is also unsupported with Bridge.

Check the resolved `stove-quarkus` artifact for
`com.trendyol.stove.quarkus.bridge` before adding the import. Do not infer a minimum
published version from the repository's current version or upgrade dependencies
just to match this guide. See [API verification](gradle-config.md#resolve-api-ambiguity-from-local-artifacts).
Reuse the project's Quarkus plugin, Quarkus BOM, Kotlin test support, and test engine;
align Stove dependencies with the existing Stove BOM.

## Build and load the augmented application

ArC transforms application classes during the Quarkus build. Loading raw main
classes in tests while the application uses another classloader breaks shared CDI
access and type identity. Build an augmented uber-jar, place it first on the actual
Gradle `Test` task's runtime classpath, and exclude raw main output from that
classpath. Keep main output available when compiling tests.

In `src/main/resources/application.properties`, preserve all discoverable beans
for e2e builds as a fixed default:

```properties
%e2e.quarkus.package.jar.type=uber-jar
%e2e.quarkus.arc.remove-unused-beans=false
```

Unused-bean removal is a **build-time** setting. Supplying it only through
`quarkus(withParameters = ...)` cannot restore removed beans. Do not introduce a
Gradle property switch or conditional tests for bean preservation. This retains
beans referenced only by tests; it does not make arbitrary test classes CDI beans.

For a **dedicated e2e application module**, use this Gradle Kotlin DSL recipe after
applying the existing Quarkus plugin and declaring the application's dependencies,
including `io.quarkus:quarkus-arc` and test dependency `com.trendyol:stove-quarkus`:

```kotlin
// This module builds only the e2e application fixture.
quarkus {
    quarkusBuildProperties.put("quarkus.profile", "e2e")
}

val augmentedJar = tasks.named<io.quarkus.gradle.tasks.QuarkusBuild>("quarkusBuild")
    .map { it.runnerJar }

tasks.test {
    dependsOn("quarkusBuild")
    classpath = files(augmentedJar) +
        (sourceSets.test.get().runtimeClasspath - sourceSets.main.get().output)
    systemProperty("quarkus.profile", "e2e")
    systemProperty("java.util.logging.manager", "org.jboss.logmanager.LogManager")
}
```

In a module that also builds production artifacts, restrict the e2e profile and
packaging selection to the e2e build invocation using the project's existing build
conventions. Do not apply the dedicated-fixture profile block globally to production
builds. The build and test runtime must both select `e2e`.

For an existing `e2eTest` or `integrationTest`, configure the real `Test` task and use
its source set's runtime classpath in place of `sourceSets.test`. Preserve its test
classes, resources, dependencies, test engine, and lifecycle configuration. Do not
create another source set just for Bridge. Apply the classpath replacement after
any convention that sets the task's classpath. See [Gradle configuration](gradle-config.md).

## Register Bridge and access beans

Keep the application's normal `@QuarkusMain` entrypoint, for example a Java main
method that calls `Quarkus.run(args)`. Register the Quarkus-specific Bridge before
the runner in the existing suite startup lifecycle. Stove detects completed Quarkus
startup automatically; no custom readiness signal or HTTP endpoint is required:

```kotlin
import com.trendyol.stove.quarkus.bridge
import com.trendyol.stove.quarkus.quarkus
import com.trendyol.stove.system.Stove
import com.trendyol.stove.system.stove
import com.trendyol.stove.system.using

// Suite startup; replace the entrypoint with the application's own main.
Stove().with {
    bridge()
    // Register HTTP and any other required systems here.
    quarkus(
        runner = { args -> com.example.BridgeApplication.main(args) },
        withParameters = listOf("quarkus.http.port=8080")
    )
}.run()

// Inside a test; replace CounterService with an application bean.
stove {
    using<CounterService> {
        increment()
    }
}

// Suite teardown, including after failures.
Stove.stop()
```

Call `using<T>` directly inside `stove {}`; there is no `bridge { using<T> { ... } }`
assertion wrapper. Reuse Kotest/JUnit startup and teardown hooks, rather than
starting an application per assertion. The reporting extension does not start Stove.

## CDI behavior

- Lookup uses default CDI qualifiers. Missing or ambiguous beans fail; there is no
  qualifier-selection or bean-replacement API in this Bridge.
- Generic types are preserved: `using<List<String>>` resolves that parameterized
  type when an eligible CDI producer or bean exists.
- Request scopes must already be active. Bridge does not activate a request context.
- Each dependent lookup can create a new instance. Bridge retains those handles
  and destroys them at `Stove.stop()`, not when a `using` block ends.
- Access is valid only after `Stove.run()` and before `Stove.stop()`.

DI access and Kafka observation are separate. Bridge does not install Kafka
interceptors. Kafka parent-first classloading configuration does not replace the
augmented shared classpath required here. See [Kafka observation wiring](system-setup.md#wire-observation-before-asserting-consumption).

## Troubleshooting

| Symptom | Check and fix |
|---|---|
| Unresolved `com.trendyol.stove.quarkus.bridge` | Verify the resolved starter API and the test source set's dependencies. |
| `Quarkus Bridge cannot use the packaged fast-jar runtime` | Build an e2e uber-jar and replace the actual test runtime classpath as above. |
| `Quarkus Bridge cannot access the running ArC container` | Check that build and runtime use `e2e`, the augmented jar comes first, and ArC/application classes share the test classloader. |
| Class cast, missing constructor, or proxy/transformation errors | Exclude raw main output from runtime, retain it for test compilation, and inspect class origins. Reflection or casting across loaders will not repair class identity. |
| `No unique CDI bean found for ...` | Rebuild with e2e bean preservation; then check CDI discovery, default qualifiers, generic type arguments, and ambiguity. |
| `Quarkus Bridge is not running` | Resolve only after startup completes and before teardown; do not retain Bridge references across runs. |
| Request context is inactive | Use the application's existing context activation or exercise that behavior through HTTP. |

## Verify the setup

Run the actual e2e test task and inspect its results; a successful build or task
discovery alone does not demonstrate Bridge access. Verify a bean referenced only
by tests can be resolved. For shared state, mutate an application-scoped service
through `using<T>` and observe the same state through the application's HTTP route.
Use typed return values to catch class identity problems.

The [Quarkus Bridge fixture](https://github.com/Trendyol/stove/tree/main/starters/quarkus/tests/quarkus-bridge)
also verifies augmented class loading, generic lookup, missing/ambiguous beans,
dependent destruction, and access rejection after shutdown. In a Stove checkout,
run `./gradlew :starters:quarkus:tests:quarkus-bridge:test` to exercise it.
