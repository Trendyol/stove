# Migrate database setup and chaos tests

Use this guide when adopting system-local managed chaos. These changes are available in **0.28.0**. Verify the project’s resolved Stove version before adopting them.
First verify the resolved source/artifact actually contains `SupportChaos`, the `enableChaos` extension, and `duringFault`;
do not apply this guide to an older release merely because the local skill is newer.

Supported registrations: `elasticsearch`, `postgresql`, `mysql`, `mssql`, `redis`, `mongodb`, and `cassandra`, keyed and unkeyed.
Cassandra chaos is limited to dedicated fixed single-node containers; Couchbase is not part of this managed-chaos rollout.

## Preserve existing setup callbacks

Registrations still accept `() -> Options`, with the same JVM `Function0` signatures. Existing inline blocks, stored
factories, function references, shared helpers, and Java callback shapes continue to work:

```kotlin
val makeOptions: () -> ElasticsearchSystemOptions = { existingOptions }
elasticsearch(makeOptions)
elasticsearch(Dc1, makeOptions)
```

Supported database options implement `SupportChaos`. Import `com.trendyol.stove.chaos.enableChaos` and call
`makeOptions().enableChaos()` when opting in. This returns the same concrete options instance, including provided-instance
subclasses, and composes with `migrations { ... }` in either order. It configures the options in place before registration.
Each registration reads the setting once; later options changes do not reconfigure existing systems. Create separate options
for registrations that need different settings. Without the opt-in, application routing remains direct.

Options constructors, migration registrations, and `provided(...)` factories retain their signatures.

## Check Redis database selection

The native Redis client now uses the configured database for migrations and assertions; earlier versions always used
`0`. The options default is `8`. Configure the application client with the exposed `database` and `password` alongside
`redisUri` where needed; `RedisOptions.provided` builds a host/port URI without those fields. Verify application writes
through a direct Stove read on the selected database before removing selection workarounds. Explicitly set `database = 0`
for suites that intend to keep using database `0`.

## Adopt managed chaos when requested

```kotlin
import com.trendyol.stove.chaos.enableChaos

elasticsearch(Dc1) {
    ElasticsearchSystemOptions(
        configureExposedConfiguration = { cfg ->
            listOf("search.url=http://${cfg.host}:${cfg.port}")
        }
    ).enableChaos()
}
```

`enableChaos()` is called on the returned options. Keep the application's connection configuration derived from
`configureExposedConfiguration`; this callback receives the proxied endpoint. Keep seeding,
migrations, and checks on the normal Stove system DSL, which retains its direct connection.

For a concrete application contract, suppose `/search/health` reports `503` while its primary search dependency is
unavailable and `200` when it has reconnected:

```kotlin
stove {
    elasticsearch(Dc1) {
        duringFault {
            http {
                getResponse<Map<String, String>>("/search/health") { it.status shouldBe 503 }
            }
        }
        eventually(10.seconds) {
            stove {
                http {
                    getResponse<Map<String, String>>("/search/health") { it.status shouldBe 200 }
                }
            }
        }
    }
}
```

Use `com.trendyol.stove.http.http`, Kotest's `eventually` and `shouldBe`, and `kotlin.time.Duration.Companion.seconds`.
Adapt the endpoint and expected response to the application's actual failure contract. The fault body has the ordinary
`ValidationDsl` receiver, so direct database checks and other system calls are also available.

Import `com.trendyol.stove.chaos.duringFault`. The fault defaults to unavailable. Additional faults are
`stall()` and `latency(250.milliseconds, TrafficDirection.Responses)` from the same package. All fault blocks execute once;
never wrap a publish/create flow in automatic retries. A latency is per TCP chunk, not per application request. A stalled
request can complete after its caller times out; use stable operation IDs when checking retries or duplicates.

Route reservation lasts until the enclosing `stove { }` block exits. Nested faults on that route and competing
fault owners are rejected. This is fault ownership, not isolation of shared application traffic: serialize tests using
the same application. Successful healing is only connectivity restoration; use explicit application behavior and direct
state assertions to establish recovery.

## Check adapter support before migrating a topology

- Elasticsearch: single HTTP(S) endpoint; application sniffing/alternate endpoint discovery must not bypass the proxy.
- PostgreSQL/MySQL/SQL Server: supported single-server JDBC authority; preserve the exposed JDBC URL. Reject unsupported
  multi-host/load-balancing/failover/named-instance/socket-factory forms instead of rewriting them approximately.
- Redis: standalone exposed `redisUri`; Sentinel/cluster discovery is not covered.
- MongoDB: use exposed `connectionString` or `replicaSetUrl`, preserving `directConnection=true`. SRV, multiple seeds,
  load-balanced mode, and TLS are rejected. Do not reconstruct the URI from host/port.
- Cassandra: dedicated fixed single-node container using the Java driver 4.x and only the exposed contact point; provided
  instances/discovered peers are rejected. Do not add alternate seeds, change topology, or enable custom discovery.
  Other language drivers are not covered by this adapter's routing guarantee.
- Couchbase: no managed adapter; advertised node/service addresses require a fuller routing design.
- Proxy endpoints are host loopback addresses. Container or remote AUTs cannot consume them automatically. Do not weaken
  TLS hostname verification to make a proxy work; preserve credentials/certificates and check the application's hostname
  requirements.

Provided infrastructure remains externally owned; keep its migration and cleanup choices unchanged. Enabling a proxy does
not reconfigure an already deployed application to use it.

## Migrate a manually owned proxy

Only when adopting managed routing: remove setup `proxy.start(...)` and manual endpoint substitution, call `enableChaos()` on the
selected registration's options, move the experiment to that system's `duringFault`, and remove the now-unused proxy shutdown.
Retain `Stove.stop()` in framework teardown. Do not stack both routing schemes.

`TcpChaosProxy`, `NetworkPartitionTarget`, and `withNetworkPartition` remain valid for manually managed topologies; migrating
to managed routing is optional.

Use the [executable recovery use cases](https://github.com/Trendyol/stove/blob/main/docs/chaos-testing.md#executable-recovery-use-cases)
for working examples of keyed primary/secondary isolation, three fault/recovery cycles, and a queued write that completes
once after a stall heals. Do not use an empty fault block or a changed endpoint alone as evidence of application behavior.

Verify the migrated suite with a healthy application request, a faulted application request plus a successful direct Stove
assertion, and a fresh application request after healing. Exercise failure/cancellation cleanup where the suite owns custom
resources. Read [release migration notes](https://github.com/Trendyol/stove/blob/main/docs/release-notes/0.28.0.md) and
[chaos routing limits](https://github.com/Trendyol/stove/blob/main/docs/chaos-testing.md) for the matching source revision.

## Named experiments and directional stall observations

Use `chaosExperiment(name, fault, timeout, baseline, faulted, recovery)` inside the system validation block when ownership
must begin before the healthy baseline. All phases execute once under a positive finite coroutine deadline; healing
precedes recovery and still runs after fault-body failure/cancellation. Standalone `duringFault` calls on that route
are rejected throughout an experiment. Phases wait for their child coroutines; externally scoped jobs remain caller-owned.
Poll only read-only observations explicitly.
Keep client timeouts bounded and shared-AUT tests serial or isolated; route ownership does not isolate ordinary traffic.

`stall(TrafficDirection.Responses)` holds acknowledgements while requests can commit. The fault block receives the faulted system
(`duringFault(stall(TrafficDirection.Responses)) { redis -> ... }`); never capture it with `val system = this`. Call
`redis.sendIntoStall(timeout) { http { ... } }` to start a request and get its `Deferred` after route-wide stall evidence,
or `redis.awaitStalledTraffic(timeout)` to only wait. Earlier or unrelated traffic within the fault can satisfy either
wait; neither identifies the operation or proves business commit. Assert durable state directly and use unique
operation IDs. Observe one direction at a time. Evidence binds to that fault generation and expires when the fault ends; a later fault cannot satisfy the wait. The direct model in 0.28.0 is
`NetworkFault.Stall(direction)`; use `stall()` for the default bidirectional behavior.

The HTTP counter application in `lib/stove-redis/src/test` demonstrates bounded AUT retries with atomic Redis operation-ID
deduplication and a fresh request after healing; the older queued-INCR example only demonstrates delayed completion.
