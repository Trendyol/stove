# Unreleased

These changes are under development after `0.27.6`. They are not part of an already published release.

## System-local database chaos

Elasticsearch, PostgreSQL, MySQL, SQL Server, Redis, MongoDB, and single-node Cassandra can route the application's database connection through a
Stove-managed TCP proxy. Enable it in the system registration block, then inject faults inside that system's validation
block:

```kotlin
Stove().with {
    elasticsearch {
        enableChaos()
        ElasticsearchSystemOptions(
            configureExposedConfiguration = { cfg ->
                listOf("search.url=http://${cfg.host}:${cfg.port}")
            }
        )
    }
    // Register your application runner here.
}.run()
```

```kotlin
stove {
    elasticsearch {
        duringFault {
            // Ordinary cross-system assertions while the application's connection is unavailable.
        }
        // Recovery checks; the route remains reserved until this system block exits.
    }
}
```

`duringFault` defaults to `NetworkFault.Unavailable`; `stall()` and `latency(duration, direction)` are also available.
Stove retains direct database access and restores connectivity on completion, failure, or cancellation. Fault application
and restoration appear in reports. Managed proxy startup rollback and shutdown are owned by Stove.

The initial adapters support specific single-endpoint topologies. Cassandra is limited to a fixed single-node container;
Couchbase is not included. See the
[chaos guide](../chaos-testing.md#supported-systems-and-routing-limits) for discovery, TLS, provided-instance, and
container-networking restrictions.

## Redis database selection

Stove's native Redis client now honors the configured database number for migrations and direct assertions. Previously
it connected to database `0` even when the options selected another database (the default is `8`). Application traffic
and direct assertions now address the same configured database. Remove any manual database-selection workaround, or set
`database = 0` explicitly if the suite intends to use database `0`.

## Migration: database setup callback signatures

**Breaking API change:** the old no-receiver setup callback overloads are removed, without deprecated compatibility
bridges. Both keyed and unkeyed registrations now take `ChaosSetupScope.() -> Options` for `elasticsearch`, `postgresql`,
`mysql`, `mssql`, `redis`, `mongodb`, and `cassandra`.

Ordinary inline Kotlin setup blocks keep their shape and do not enable chaos automatically:

```kotlin
elasticsearch {
    ElasticsearchSystemOptions(configureExposedConfiguration = { cfg ->
        listOf("search.url=http://${cfg.host}:${cfg.port}")
    })
}
```

### Recompile callers and shared test libraries

The JVM callback parameter changes from `Function0` to `Function1`. Recompile all consumers of the affected setup methods,
including shared test-fixture artifacts. Updating runtime jars without recompiling old call sites can produce
`NoSuchMethodError`. Align the Stove module versions used by the application test suite.

### Wrap existing options factories

A stored zero-argument factory or function reference can no longer be passed directly as the setup callback. Keep the
factory unchanged and invoke it inside the receiver block:

```kotlin
val makeOptions: () -> ElasticsearchSystemOptions = { existingOptions }

// Before:
// elasticsearch(makeOptions)

// After:
elasticsearch { makeOptions() }
// Or with a key:
elasticsearch(Dc1) { makeOptions() }
```

A helper that deliberately exposes setup capabilities should accept the new receiver type:

```kotlin
import com.trendyol.stove.chaos.ChaosSetupScope

fun WithDsl.searchSystem(configure: ChaosSetupScope.() -> ElasticsearchSystemOptions): Stove =
    elasticsearch(configure)
```

Java callers must supply a lambda with a `ChaosSetupScope` argument instead of a zero-argument lambda. Immutable options
constructors, `provided(...)` factories, migrations, and configuration mapping callbacks are retained.

### Enable chaos explicitly

Place `enableChaos()` before the options expression; it is a setup capability, not an options mutation:

```kotlin
elasticsearch(Dc1) {
    enableChaos()
    makeOptions()
}
```

Keep the application wired from `configureExposedConfiguration`. With chaos enabled, that callback receives the
application-facing endpoint. The system's own native client, migrations, and validation DSL retain direct configuration.
For MongoDB, use the exposed connection URI to retain the adapter's required direct-connection mode.

### Move manual experiments only when adopting managed routing

Replace the setup-time `proxy.start(...)` and proxy URL substitution with `enableChaos()` and the usual exposed
configuration. Replace `withNetworkPartition(proxy) { ... }` with `duringFault { ... }` inside the selected system's
validation block. Remove manual proxy shutdown only after that proxy is no longer used.

The low-level `TcpChaosProxy`, `NetworkPartitionTarget`, and `withNetworkPartition` APIs remain available and do not
require migration. Do not stack a manual proxy over managed routing. Keep recovery assertions within the enclosing
system block, and serialize tests that share the same application.

### Lifecycle behavior

Stove rolls back resources when startup fails. Shutdown restores active faults, stops the application, closes its
managed routes, and disposes dependencies in reverse registration order. It attempts the remaining stages when a stage
fails. `Stove.close()` now throws the first cleanup failure with subsequent failures suppressed; previously it logged
and swallowed a failure and could skip later resources. Callers that relied on teardown succeeding despite failed
cleanup should handle or fix that failure explicitly. Database cleanup must also tolerate startup ending before its
client is initialized.
