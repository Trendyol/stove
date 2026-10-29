# Migrate database setup and chaos tests

Use this guide when upgrading a suite to the receiver-based setup callbacks and system-local managed chaos. These changes
are currently **unreleased after 0.27.6**. First verify the resolved source/artifact actually contains `ChaosSetupScope`
and `duringFault`; do not apply this guide to an older release merely because the local skill is newer.

Affected registrations: `elasticsearch`, `postgresql`, `mysql`, `mssql`, `redis`, `mongodb`, and `cassandra`, keyed and unkeyed.
Cassandra chaos is limited to dedicated fixed single-node containers; Couchbase is not part of this managed-chaos rollout.

## Migrate the setup signature

The old `() -> Options` registration overloads have been removed. They are not deprecated aliases. The replacement is
`ChaosSetupScope.() -> Options`. Ordinary inline blocks continue to return their immutable options as the final expression.

For a saved factory or function reference, wrap the call:

```kotlin
// Existing factory stays unchanged.
val makeOptions: () -> ElasticsearchSystemOptions = { existingOptions }

// Replace elasticsearch(makeOptions) with:
elasticsearch { makeOptions() }
// Keyed equivalent:
elasticsearch(Dc1) { makeOptions() }
```

If a shared helper should expose setup capabilities, change its parameter to
`ChaosSetupScope.() -> ElasticsearchSystemOptions` and forward that receiver callback. Import
`com.trendyol.stove.chaos.ChaosSetupScope`. If it only builds options, preserve its zero-argument factory and wrap the call.
Java setup lambdas now take the scope argument. Recompile shared test-fixture libraries and consumers: the JVM method
parameter changed from `Function0` to `Function1`, so updating runtime jars alone is insufficient.

Do not rewrite options constructors, migration registrations, or `provided(...)` factories to mutable builders. They retain
their purpose and API. Without `enableChaos()`, application routing remains direct.

## Check Redis database selection

The native Redis client now uses the configured database for migrations and assertions; earlier versions always used
`0`. The options default is `8`. Remove manual selection workarounds, or explicitly set `database = 0` for suites that
intend to keep using database `0`. Verify application writes through a direct Stove read on the selected database.

## Adopt managed chaos when requested

```kotlin
elasticsearch(Dc1) {
    enableChaos()
    ElasticsearchSystemOptions(
        configureExposedConfiguration = { cfg ->
            listOf("search.url=http://${cfg.host}:${cfg.port}")
        }
    )
}
```

`enableChaos()` belongs to the registration scope, before the options expression. Keep the application's connection
configuration derived from `configureExposedConfiguration`; this callback receives the proxied endpoint. Keep seeding,
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

Route reservation lasts until the enclosing system validation block exits. Nested faults on that route and competing
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

Only when adopting managed routing: remove setup `proxy.start(...)` and manual endpoint substitution, enable chaos in the
selected registration, move the experiment to that system's `duringFault`, and remove the now-unused proxy shutdown.
Retain `Stove.stop()` in framework teardown. Do not stack both routing schemes.

`TcpChaosProxy`, `NetworkPartitionTarget`, and `withNetworkPartition` remain valid for manually managed topologies; migrating
the setup callback does not require rewriting those experiments.

Use the [executable recovery use cases](https://github.com/Trendyol/stove/blob/main/docs/chaos-testing.md#executable-recovery-use-cases)
for working examples of keyed primary/secondary isolation, three fault/recovery cycles, and a queued write that completes
once after a stall heals. Do not use an empty fault block or a changed endpoint alone as evidence of application behavior.

Verify the migrated suite with a healthy application request, a faulted application request plus a successful direct Stove
assertion, and a fresh application request after healing. Exercise failure/cancellation cleanup where the suite owns custom
resources. Read [release migration notes](https://github.com/Trendyol/stove/blob/main/docs/release-notes/unreleased.md) and
[chaos routing limits](https://github.com/Trendyol/stove/blob/main/docs/chaos-testing.md) for the matching source revision.
