# Chaos Testing

Chaos tests introduce a controlled failure and verify how the application behaves while the failure is active and after
it recovers. Stove's first chaos primitive is an application-to-dependency TCP network partition.

<div class="stove-tldr" markdown>
<span class="stove-tldr-title">In 30 seconds</span>
Put a <code>TcpChaosProxy</code> between the application and a real dependency, give the application the proxy endpoint,
then run assertions inside <code>withNetworkPartition(proxy) { ... }</code>. The dependency stays alive, Stove keeps its
direct test connection, and the link is healed even when the experiment fails.
</div>

## What `withNetworkPartition` means

A network partition means that two running components cannot communicate. It differs from stopping Elasticsearch: the
server remains healthy and directly observable, but the application cannot reach it through the selected link.

```kotlin
withNetworkPartition(ElasticsearchChaos.dc1) {
  // dc1 is unreachable from the application in this block
}
// dc1 is reachable again
```

`withNetworkPartition` partitions each distinct target in argument order and heals successfully partitioned targets in
reverse order. Healing runs when the block succeeds, fails, or is cancelled. This makes the experiment safe to use in a
test suite without leaking the failure into later tests.

The current proxy models a complete, bidirectional TCP connectivity loss. It does not yet model latency, packet loss,
bandwidth limits, process death, or Kafka rebalances.

## Make one Elasticsearch server inaccessible

This example starts two real Elasticsearch systems named `dc1` and `dc2`. Stove talks directly to both containers for
migrations and assertions. Only the application receives their proxy endpoints.

### 1. Create the chaos links

Keep proxies at suite scope so their lifecycle matches the physical dependencies and the application.

```kotlin
import com.trendyol.stove.chaos.TcpChaosProxy
import com.trendyol.stove.system.abstractions.SystemKey
import java.io.Closeable

object Dc1 : SystemKey
object Dc2 : SystemKey

object ElasticsearchChaos : Closeable {
  val dc1 = TcpChaosProxy("dc1")
  val dc2 = TcpChaosProxy("dc2")

  override fun close() {
    dc2.close()
    dc1.close()
  }
}
```

### 2. Route the application through the proxies

Start each proxy from `configureExposedConfiguration`, where the real container host and port are available. Return the
proxy URL as application configuration.

```kotlin
import com.trendyol.stove.chaos.TcpChaosProxy
import com.trendyol.stove.chaos.TcpEndpoint
import com.trendyol.stove.elasticsearch.ElasticsearchSystemOptions
import com.trendyol.stove.elasticsearch.elasticsearch
import com.trendyol.stove.spring.springBoot
import com.trendyol.stove.system.Stove

override suspend fun beforeProject() {
  Stove()
    .with {
      elasticsearch(Dc1) {
        elasticsearchOptions("elasticsearch.dc1.url", ElasticsearchChaos.dc1)
      }
      elasticsearch(Dc2) {
        elasticsearchOptions("elasticsearch.dc2.url", ElasticsearchChaos.dc2)
      }

      // Register the application runner last. It receives the exposed proxy URLs.
      springBoot(runner = { params -> Application.run(params) })
    }.run()
}

private fun elasticsearchOptions(
  property: String,
  proxy: TcpChaosProxy
) = ElasticsearchSystemOptions(
  configureExposedConfiguration = { dependency ->
    proxy.start(TcpEndpoint(dependency.host, dependency.port))
    listOf("$property=${proxy.endpoint.uri("http")}")
  }
)

override suspend fun afterProject() {
  try {
    Stove.stop()
  } finally {
    ElasticsearchChaos.close()
  }
}
```

The separation is important:

- the application uses `ElasticsearchChaos.dc1.endpoint`;
- `elasticsearch(Dc1) { ... }` keeps using the container's direct endpoint;
- partitioning the proxy isolates only the application, so the test can still inspect Elasticsearch.

### 3. Run the experiment

The test below makes the primary Elasticsearch server inaccessible, verifies that the application continues through
`dc2`, and then verifies recovery after `dc1` is automatically healed.

```kotlin
import com.trendyol.stove.chaos.withNetworkPartition

test("continues indexing when dc1 Elasticsearch is inaccessible") {
  val document = contentDocument()

  withNetworkPartition(ElasticsearchChaos.dc1) {
    stove {
      kafka { publishAndAwaitConsumption(document) }
      elasticsearch(Dc2) { shouldContain(document) }
    }
  }

  stove {
    elasticsearch(Dc1) { shouldContain(document) }
    elasticsearch(Dc2) { shouldContain(document) }
  }
}
```

`publishAndAwaitConsumption` and `shouldContain` are application-specific helpers in this example. They should wait for
the asynchronous operation rather than rely on sleeps.

Partition more than one link by passing multiple targets:

```kotlin
withNetworkPartition(ElasticsearchChaos.dc1, ElasticsearchChaos.dc2) {
  // verify behavior when every Elasticsearch link is unavailable
}
```

## Create another network-partition target

`TcpChaosProxy` implements `NetworkPartitionTarget`. A different transport or infrastructure controller can participate
in the same experiment by implementing that interface:

```kotlin
class ManagedNetworkLink(
  override val name: String,
  private val controller: NetworkController
) : NetworkPartitionTarget {
  override fun partition() = controller.disconnect(name)
  override fun heal() = controller.reconnect(name)
}
```

Make `partition()` and `heal()` idempotent, keep the target narrowly scoped to one application-to-dependency link, and
ensure `heal()` restores the state required by the next test. Prefer `withNetworkPartition` over manual lifecycle calls
so cleanup remains guaranteed.

## Practical guidance

- Proxy only dependencies involved in a resilience scenario; do not add a proxy to every physical component by default.
- Use topology-neutral test names such as `dc1` and `dc2`, not production data-center names.
- Keep connection and request timeouts short enough that a partition fails within the test's assertion window.
- Assert degraded behavior while the partition is active and recovered behavior after the block.
- Close proxies after `Stove.stop()` so the application shuts down before its dependency links disappear.
