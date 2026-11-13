# Unreleased

These changes are under development after `0.27.6`. They are not part of an already published release.

## Named chaos experiments and directional stalls

- `chaosExperiment(name, fault, timeout, baseline, faulted, recovery)` reserves the application route before baseline,
  executes each phase once, waits for its child coroutines, heals before recovery, and reports named phases under one
  finite coroutine deadline. Standalone faults on the route are rejected while the experiment runs.
- `stall(direction)` supports request-only and response-only stalls, defaulting to both directions.
- `awaitStalledTraffic(timeout)` waits for bytes encountered by the current stall; `sendIntoStall(timeout) { ... }`
  starts a request in the background and returns its pending result after route-wide stall evidence is observed. Earlier
  or unrelated traffic within that fault can satisfy the wait; it does not identify the request. Evidence does not survive
  healing or a new fault, and does not replace direct persisted-state assertions.
- Fault and experiment blocks receive the faulted system as their parameter (`duringFault { redis -> ... }`), and
  route reservations last until the enclosing `stove { }` block exits.
- An executable HTTP/Redis recovery test verifies atomic operation-ID deduplication across committed-but-unacknowledged
  writes, bounded application retries, and fresh operations after healing.

For callers using the unreleased model directly, `NetworkFault.Stall` is now a value with a direction:
use `NetworkFault.Stall()` or `stall()` for the previous bidirectional behavior. Recompile consumers of the changed API.

## System-local database chaos

Elasticsearch, PostgreSQL, MySQL, SQL Server, Redis, MongoDB, and single-node Cassandra can route the application's database connection through a
Stove-managed TCP proxy. Enable it on the system's options, then inject faults inside that system's validation
block:

```kotlin
import com.trendyol.stove.chaos.enableChaos

Stove().with {
    elasticsearch {
        ElasticsearchSystemOptions(
            configureExposedConfiguration = { cfg ->
                listOf("search.url=http://${cfg.host}:${cfg.port}")
            }
        ).enableChaos()
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
it connected to database `0` even when the options selected another database (the default is `8`). Configure the application
client to use the exposed `database` and `password` alongside `redisUri` where needed: the URI from `RedisOptions.provided`
contains only the host and port. URI-only application configuration may still select database `0` or omit authentication.
Verify an application write with a direct Stove read on the selected database before removing selection workarounds. Set
`database = 0` explicitly if the suite intends to use database `0`.

## Opt in through database options

`elasticsearch`, `postgresql`, `mysql`, `mssql`, `redis`, `mongodb`, and `cassandra` keep their existing
`() -> Options` setup callbacks, for both keyed and unkeyed registrations. Their JVM `Function0` signatures,
options constructors, `provided(...)` factories, and migration APIs are preserved. Existing saved factories,
function references, shared helpers, and Java callback shapes continue to work.

The supported options implement `SupportChaos`. Import `com.trendyol.stove.chaos.enableChaos` and chain the opt-in
on the options returned by the setup block:

```kotlin
import com.trendyol.stove.chaos.enableChaos

val makeOptions: () -> ElasticsearchSystemOptions = { existingOptions }

// Existing setup remains valid.
elasticsearch(makeOptions)

// Opt in for a subsequent registration.
elasticsearch(Dc1) { makeOptions().enableChaos() }
```

`enableChaos()` returns the same concrete options instance, including provided-instance subclasses. It can be
chained before or after `migrations { ... }`. Like migration configuration, it changes the options in place.
The setting is read during registration; later changes do not reconfigure systems already registered. Build
separate options when registrations need different settings.

Keep the application wired from `configureExposedConfiguration`. With chaos enabled, that callback receives the
application-facing endpoint. The system's own native client, migrations, and validation DSL retain direct configuration.
For MongoDB, use the exposed connection URI to retain the adapter's required direct-connection mode.

### Move manual experiments only when adopting managed routing

Replace the setup-time `proxy.start(...)` and proxy URL substitution with `options.enableChaos()` and the usual exposed
configuration. Replace `withNetworkPartition(proxy) { ... }` with `duringFault { ... }` inside the selected system's
validation block. Remove manual proxy shutdown only after that proxy is no longer used.

The low-level `TcpChaosProxy`, `NetworkPartitionTarget`, and `withNetworkPartition` APIs remain available and do not
require migration. Do not stack a manual proxy over managed routing. Keep recovery assertions within the enclosing
system block, and serialize tests that share the same application.

### Lifecycle behavior

Stove rolls back resources when startup fails. Shutdown restores active faults, stops the application, closes its
managed routes, and disposes dependencies in reverse registration order. It attempts the remaining stages when a stage
fails. `Stove.close()` still logs cleanup failures instead of throwing; the first failure is logged with subsequent
failures suppressed. Database cleanup must also tolerate startup ending before its
client is initialized.
