package com.trendyol.stove.elasticsearch

import co.elastic.clients.elasticsearch.ElasticsearchClient
import co.elastic.clients.transport.ElasticsearchTransport
import co.elastic.clients.transport.rest_client.RestClientOptions
import com.trendyol.stove.system.Stove
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.elasticsearch.client.RequestOptions
import org.testcontainers.utility.DockerImageName
import java.io.Closeable
import java.lang.reflect.Proxy

class ElasticsearchShutdownTest : FunSpec({
  test("cleanup and transport failures still stop the container without escaping close") {
    ElasticsearchShutdownFixture().use { fixture ->
      fixture.system.close()
      fixture.events shouldBe listOf("cleanup", "transport.close", "container.stop")
      fixture.cleanupFailure.suppressed.toList() shouldBe listOf(fixture.closeFailure)
    }
  }

  test("closing before client initialization still stops the container") {
    ElasticsearchShutdownFixture(initialize = false).use { fixture ->
      fixture.system.close()
      fixture.events shouldBe listOf("container.stop")
    }
  }
})

private class ElasticsearchShutdownFixture(initialize: Boolean = true) : Closeable {
  val events = mutableListOf<String>()
  val cleanupFailure = IllegalStateException("cleanup failed")
  val closeFailure = IllegalStateException("transport close failed")
  private val stove = Stove()
  private val container = object : StoveElasticSearchContainer(
    DockerImageName.parse("docker.elastic.co/elasticsearch/elasticsearch:8.17.0")
  ) {
    override fun stop() {
      events += "container.stop"
    }
  }

  // The cleanup contract receives a native client; only its transport disposal is simulated.
  private val transport = Proxy.newProxyInstance(
    ElasticsearchTransport::class.java.classLoader,
    arrayOf(ElasticsearchTransport::class.java)
  ) { _, method, _ ->
    when (method.name) {
      "options" -> RestClientOptions(RequestOptions.DEFAULT, false)

      "close" -> {
        events += "transport.close"
        throw closeFailure
      }

      else -> error("Unexpected transport call: ${method.name}")
    }
  } as ElasticsearchTransport
  val system = ElasticsearchSystem(
    stove,
    ElasticsearchContext(
      container,
      ElasticsearchSystemOptions(
        cleanup = {
          events += "cleanup"
          throw cleanupFailure
        },
        configureExposedConfiguration = { emptyList() }
      )
    )
  ).apply {
    if (initialize) esClient = ElasticsearchClient(transport)
  }

  override fun close() = stove.close()
}
