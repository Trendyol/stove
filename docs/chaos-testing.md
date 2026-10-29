# Chaos Testing

Chaos tests introduce a controlled failure and verify application behavior during the failure and after recovery.
Enable chaos on a database system to route the application's connection through a Stove-managed TCP proxy. The database
stays alive, and Stove keeps its direct connection for migrations, seeding, and assertions.

<div class="stove-tldr" markdown>
<span class="stove-tldr-title">In 30 seconds</span>
Call <code>enableChaos()</code> in a supported system's setup block, return your usual options, then call
<code>duringFault { ... }</code> inside that system's validation block. The default fault makes the application's
connection unavailable. Connectivity is restored when the block succeeds, fails, or is cancelled.
</div>

## Enable chaos where the system is configured

This example configures two Elasticsearch systems. Only `Dc1` is routed through a chaos proxy. The application must use
the endpoint supplied by `configureExposedConfiguration`; an independently configured direct endpoint bypasses the fault.

```kotlin
import com.trendyol.stove.elasticsearch.ElasticsearchSystemOptions
import com.trendyol.stove.elasticsearch.elasticsearch
import com.trendyol.stove.http.HttpClientSystemOptions
import com.trendyol.stove.http.httpClient
import com.trendyol.stove.spring.springBoot
import com.trendyol.stove.system.Stove
import com.trendyol.stove.system.abstractions.SystemKey

object Dc1 : SystemKey
object Dc2 : SystemKey

// Run in your test framework's suite/project startup hook.
Stove().with {
    httpClient { HttpClientSystemOptions(baseUrl = "http://localhost:8080") }

    elasticsearch(Dc1) {
        enableChaos()
        ElasticsearchSystemOptions(
            configureExposedConfiguration = { cfg ->
                listOf("elasticsearch.dc1.url=http://${cfg.host}:${cfg.port}")
            }
        )
    }
    elasticsearch(Dc2) {
        ElasticsearchSystemOptions(
            configureExposedConfiguration = { cfg ->
                listOf("elasticsearch.dc2.url=http://${cfg.host}:${cfg.port}")
            }
        )
    }

    springBoot(runner = { params -> Application.run(params) })
}.run()
```

Options remain the last expression in the setup block. Existing migrations, cleanup callbacks, and provided-instance
options still apply. Without `enableChaos()`, the application receives the direct configuration as before.

Pair startup with `Stove.stop()` in the framework's teardown hook. Stove owns the proxies: it restores active faults,
stops the application, closes the proxies, and disposes dependencies. No extra proxy singleton or manual `close()` is
needed for managed chaos.

## Fault the selected system, then verify recovery

`duringFault` is scoped to its enclosing system, including its `SystemKey`. Its block exposes the ordinary cross-system
validation DSL, so it can call HTTP, Kafka, or another database while the selected application's connection is faulted.

The following example assumes an application with a JSON `/search/health` endpoint that reports `503` while its primary
search dependency is unavailable and `200` after it reconnects:

```kotlin
import com.trendyol.stove.chaos.duringFault
import com.trendyol.stove.elasticsearch.elasticsearch
import com.trendyol.stove.http.http
import com.trendyol.stove.system.stove
import io.kotest.assertions.nondeterministic.eventually
import io.kotest.matchers.shouldBe
import java.util.UUID
import kotlin.time.Duration.Companion.seconds

val id = UUID.randomUUID().toString()
val index = "chaos-products"
stove {
    elasticsearch(Dc1) { save(id, mapOf("name" to "primary"), index) }
    elasticsearch(Dc2) { save(id, mapOf("name" to "secondary"), index) }
    elasticsearch(Dc1) {
        duringFault {
            http {
                getResponse<Map<String, String>>("/search/health") {
                    it.status shouldBe 503
                }
            }
            elasticsearch(Dc2) {
                shouldGet<Map<String, String>>(index, id) { it["name"] shouldBe "secondary" }
            }
            elasticsearch(Dc1) {
                shouldGet<Map<String, String>>(index, id) { it["name"] shouldBe "primary" }
            }
        }

        // The fault is healed. Keep recovery checks inside this outer system block.
        eventually(10.seconds) {
            stove {
                http {
                    getResponse<Map<String, String>>("/search/health") {
                        it.status shouldBe 200
                    }
                }
            }
        }
    }
}
```

Each block executes once. For asynchronous reconnection, use the test framework's bounded assertion polling or an
appropriate read-only wait helper around the recovery check. Do not retry an entire flow that publishes messages or
creates orders. Keep application connection/request timeouts below the test's assertion window.

The route remains reserved until the enclosing system validation block exits, including checks after `duringFault`.
Another validation block cannot fault that same route during recovery. Nested faults on the same route are rejected.
Calling `duringFault` without enabling chaos fails with a setup instruction.

Reservations coordinate fault ownership; they do not isolate application traffic. A shared application's pooled
connections and background work can all use the affected route. Run tests sharing that application serially, or use
separate application fixtures.

## Choose the fault

Pass the fault as the first argument to `duringFault`. Import `stall`, `latency`, and `TrafficDirection` from
`com.trendyol.stove.chaos`, and duration units such as `milliseconds` from `kotlin.time.Duration.Companion`.

| Fault | Behavior |
|---|---|
| `duringFault { ... }` / `NetworkFault.Unavailable` | Closes existing connections and accepts then closes new connections. It does not guarantee a particular TCP error such as reset or refusal. |
| `stall()` | Pauses forwarding without deliberately dropping bytes. TCP backpressure and application timeouts may develop. Healing releases forwarding; timed-out requests may still reach the dependency. |
| `latency(duration, direction)` | Delays each forwarded TCP chunk in `Requests`, `Responses`, or `Both` directions. The default direction is `Both`. This is not a per-request latency guarantee. Healing releases queued chunks. |

Latency must be positive and finite. These faults do not stop the dependency, simulate packet loss, or orchestrate
broker rebalances. A successful write after a timeout is possible; use unique operation IDs when checking retries and
duplicate handling.

## Executable recovery use cases

The repository includes complete fixtures for these scenarios. Their application-facing clients consume the exposed
configuration, while persisted-state checks use Stove's direct connection.

| Scenario | Assertions that establish the outcome | Runnable coverage |
|---|---|---|
| Primary outage while secondary stays usable | An established primary connection and a fresh primary connection fail. A secondary application connection increments a counter during each outage. Direct primary assertions still work. A fresh primary request succeeds after each of three recoveries. | [RedisChaosUseCasesTest](https://github.com/Trendyol/stove/blob/main/lib/stove-redis/src/test/kotlin/com/trendyol/stove/redis/RedisChaosUseCasesTest.kt) |
| Stalled write released after healing | Queue one `INCR`, let a bounded wait expire without cancelling it, and confirm no value exists through the direct connection during the stall. After healing, the same future returns `1`, both clients read `1`, and the fault callback count is one. | [RedisChaosUseCasesTest](https://github.com/Trendyol/stove/blob/main/lib/stove-redis/src/test/kotlin/com/trendyol/stove/redis/RedisChaosUseCasesTest.kt) |
| Application resumes and sees data written during its outage | Save and read a document through Stove during an unavailable fault, then fetch that document through a fresh application-facing HTTP request after healing. | [ElasticsearchChaosTest](https://github.com/Trendyol/stove/blob/main/lib/stove-elasticsearch/src/test/kotlin/com/trendyol/stove/elasticsearch/ElasticsearchChaosTest.kt) |
| Discovery does not escape the supported route | MongoDB uses a real replica set with direct connection enabled; Cassandra verifies its sole discovered endpoint remains the proxy before and after healing. Both check failed application operations and successful direct assertions during the fault. | [MongoDB test](https://github.com/Trendyol/stove/blob/main/lib/stove-mongodb/src/test/kotlin/com/trendyol/stove/mongodb/MongodbChaosAdapterTest.kt), [Cassandra test](https://github.com/Trendyol/stove/blob/main/lib/stove-cassandra/src/test/kotlin/com/trendyol/stove/cassandra/CassandraChaosAdapterTest.kt) |

For example, the stalled-write test keeps the original command future across the fault boundary:

```kotlin
// connection is the application's already connected Redis client; counter is a unique test key.
ValidationDsl(fixture).redis(Primary) {
    lateinit var pending: RedisFuture<Long>
    duringFault(stall()) {
        pending = connection.async().incr(counter)
        shouldThrow<TimeoutException> { pending.get(150, TimeUnit.MILLISECONDS) }
        redis(Primary) {
            client().connect().use { it.sync().get(counter) } shouldBe null
        }
    }
    pending.await() shouldBe 1L
    connection.sync().get(counter) shouldBe "1"
    client().connect().use { it.sync().get(counter) } shouldBe "1"
}
```

The complete linked fixture supplies the imports, timeouts, keys, lifecycle, and callback-count assertion. Here the
bounded wait expires; the command's own timeout does not. This deliberately demonstrates delayed completion without
reissuing a write. If your application retries a command after a timeout, separately assert its operation-ID or
idempotency policy; connectivity restoration alone cannot establish duplicate safety.

The two Redis routes intentionally share a server to prove route selection. Their different keys do not create separate
data stores or isolate shared application background work.

## Supported systems and routing limits

| System | Application configuration to use | Scope |
|---|---|---|
| Elasticsearch | Exposed `host` and `port` | A single HTTP(S) endpoint. Disable client sniffing or other alternate endpoint discovery that would bypass the proxy. |
| PostgreSQL | Exposed `jdbcUrl`, or its exposed `host` and `port` | Standard single-server PostgreSQL JDBC URL. |
| MySQL | Exposed `jdbcUrl`, or its exposed `host` and `port` | Standard single-server MySQL JDBC URL; load-balancing/replication URLs are rejected. |
| SQL Server | Exposed `jdbcUrl`, or its exposed `host` and `port` | Standard single-server SQL Server JDBC URL; named instances and failover overrides are rejected. |
| Redis | Exposed `redisUri` | Standalone `redis://` or `rediss://`; Sentinel and cluster discovery are not routed by this adapter. |
| MongoDB | Exposed `connectionString` / `replicaSetUrl` | A single seed with `directConnection=true`; SRV, multiple seeds, load-balanced mode, and TLS are rejected. Preserve the exposed URI instead of rebuilding it from host and port. |
| Cassandra | Exposed contact-point `host` and `port` | A dedicated fixed single-node container using the Java driver 4.x. Provided instances and discovered peers are rejected. No alternative seeds, topology changes, or custom discovery; other language drivers are not covered. |

The JDBC adapters preserve database names, query/properties, and separate credential fields. Unsupported alternate
socket factories and endpoint overrides fail early rather than silently escaping the proxy. Provided JDBC URLs must
match their separately supplied host and port.

Couchbase does not expose managed chaos here. Its driver discovers additional node/service endpoints; rewriting only
the bootstrap endpoint would give incomplete fault coverage. Kafka, other systems,
and application runners also do not gain `enableChaos()` from these database adapters.

The proxy listens on the test host's loopback interface. Host/JVM application runners can consume this endpoint.
Containerized or remote applications cannot reach it merely because chaos was enabled; they need an explicitly reachable
proxy topology. Do not copy this setup into `containerApp` or `providedApplication` tests and assume routing is automatic.

Except for the MongoDB restriction above, TCP forwarding preserves TLS bytes and supplied credentials/certificates.
The application's hostname verification must remain valid for the proxy hostname; Stove does not disable verification
or issue a new certificate. Provided infrastructure remains running, and its existing migration/cleanup policy is
unchanged.

## Read the evidence accurately

Reports identify the selected system/key, applied fault, and connectivity restoration. Restoration means forwarding is
available again. It does not prove the application reconnected, drained its backlog, or avoided duplicate writes.

Assert those outcomes explicitly. A fresh operation that depends on the restored route is stronger evidence of recovery
than finding a document that a healthy replica may already have copied. Use direct database assertions for persisted
state and application requests or traces when the claim concerns routing, retries, or failback.

If fault restoration itself fails, the original experiment error remains primary with restoration failures attached.
A route whose restoration is uncertain cannot be used for another fault; recreate the fixture.

## Existing low-level proxies

`TcpChaosProxy`, `NetworkPartitionTarget`, and `withNetworkPartition` remain available for explicitly managed topologies
and custom controllers. Their lifecycle remains the caller's responsibility:

```kotlin
withNetworkPartition(proxy) {
    // Application-to-dependency connection is unavailable in this block.
}
```

The helper partitions each distinct target in argument order and restores successfully partitioned targets in reverse
order, including on failure or cancellation. For manually routed proxies, keep Stove on the direct endpoint and close
proxies after `Stove.stop()`. Avoid wrapping an already managed route in another proxy.

See the [setup and chaos migration notes](release-notes/unreleased.md) when upgrading existing setup helpers.
