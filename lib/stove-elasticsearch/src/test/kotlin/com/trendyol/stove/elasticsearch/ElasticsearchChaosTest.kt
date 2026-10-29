package com.trendyol.stove.elasticsearch

import co.elastic.clients.elasticsearch.ElasticsearchClient
import co.elastic.clients.json.jackson.JacksonJsonpMapper
import co.elastic.clients.transport.rest_client.RestClientTransport
import com.trendyol.stove.chaos.*
import com.trendyol.stove.system.Stove
import com.trendyol.stove.system.abstractions.SystemKey
import com.trendyol.stove.system.stove
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.longs.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import org.apache.http.HttpHost
import org.elasticsearch.client.RestClient
import java.io.IOException
import java.net.URI
import java.net.http.*
import java.time.Duration
import java.util.UUID
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.measureTime

internal object ElasticsearchChaosTestEndpoint {
  lateinit var url: String
}

class ElasticsearchChaosTest : FunSpec({
  val application = SearchApplication()

  test("unavailable isolates the application while direct assertions work and the route recovers") {
    val id = UUID.randomUUID().toString()
    application.get().statusCode() shouldBe 200

    stove {
      elasticsearch {
        duringFault {
          shouldThrow<IOException> { application.get() }
          elasticsearch {
            save(id, mapOf("id" to id, "description" to "written during fault"), TEST_INDEX)
            shouldGet<Map<String, String>>(TEST_INDEX, id) { it["description"] shouldBe "written during fault" }
          }
        }

        // A fresh request through the healed route reads data written through Stove's direct connection.
        val response = application.get("/$TEST_INDEX/_doc/$id")
        response.statusCode() shouldBe 200
        response.body() shouldContain "written during fault"
      }
    }
  }

  test("stall times out application requests and releases forwarding after the block") {
    stove {
      elasticsearch {
        duringFault(stall()) {
          shouldThrow<HttpTimeoutException> { application.get(timeout = Duration.ofMillis(300)) }
          elasticsearch {
            val id = UUID.randomUUID().toString()
            save(id, mapOf("id" to id), TEST_INDEX)
            shouldGet<Map<String, String>>(TEST_INDEX, id) { it["id"] shouldBe id }
          }
        }
        application.get().statusCode() shouldBe 200
      }
    }
  }

  test("response latency delays the application and an experiment failure still restores the route") {
    stove {
      elasticsearch {
        shouldThrow<IllegalStateException> {
          duringFault(latency(150.milliseconds, TrafficDirection.Responses)) {
            val elapsed = measureTime { application.get().statusCode() shouldBe 200 }
            elapsed.inWholeMilliseconds.shouldBeGreaterThanOrEqual(150)
            error("experiment failed")
          }
        }
        application.get().statusCode() shouldBe 200
      }
    }
  }

  test("enabling chaos for the primary key preserves credentials and the default configuration") {
    ElasticsearchRegistrationFixture().use { fixture ->
      val direct = fixture.direct
      val primary = fixture.primaryApplicationConfiguration()

      primary.host shouldBe "127.0.0.1"
      primary.port shouldNotBe direct.port
      primary.password shouldBe direct.password
      primary.certificate shouldBe direct.certificate
      fixture.defaultApplicationConfiguration() shouldBe direct
      fixture.direct shouldBe direct
    }
  }

  test("closing before client initialization still stops the container") {
    ElasticsearchCleanupFixture().use { fixture ->
      fixture.system.close()
      fixture.containerStopped shouldBe true
    }
  }

  test("cleanup failure still closes resources and preserves container shutdown failure as suppressed") {
    val cleanupFailure = IllegalStateException("cleanup failed")
    val stopFailure = IllegalStateException("container stop failed")
    ElasticsearchCleanupFixture(cleanupFailure, stopFailure, initializeClient = true).use { fixture ->
      val actual = shouldThrow<IllegalStateException> { fixture.system.close() }
      actual shouldBe cleanupFailure
      actual.suppressed.toList() shouldBe listOf(stopFailure)
      fixture.containerStopped shouldBe true
    }
  }
})

// This HTTP client represents AUT traffic; direct database operations use the Stove DSL.
private class SearchApplication {
  private val client = HttpClient.newHttpClient()

  fun get(path: String = "/", timeout: Duration = Duration.ofSeconds(3)): HttpResponse<String> = client.send(
    HttpRequest.newBuilder(URI.create(ElasticsearchChaosTestEndpoint.url + path)).timeout(timeout).GET().build(),
    HttpResponse.BodyHandlers.ofString()
  )
}

private object PrimarySearch : SystemKey

private class ElasticsearchRegistrationFixture : AutoCloseable {
  private val stove = Stove().with {
    elasticsearch { ElasticsearchSystemOptions { emptyList() } }
    elasticsearch(PrimarySearch) {
      enableChaos()
      ElasticsearchSystemOptions { emptyList() }
    }
  }
  val direct = ElasticSearchExposedConfiguration("localhost", 9200, "secret", ElasticsearchExposedCertificate(byteArrayOf(1, 2, 3)))

  fun defaultApplicationConfiguration() = stove.elasticsearch().applicationConfiguration(direct, ElasticsearchChaosAdapter)

  fun primaryApplicationConfiguration() = stove.elasticsearch(PrimarySearch).applicationConfiguration(direct, ElasticsearchChaosAdapter)

  override fun close() = stove.close()
}

private class ElasticsearchCleanupFixture(
  cleanupFailure: RuntimeException? = null,
  stopFailure: RuntimeException? = null,
  initializeClient: Boolean = false
) : AutoCloseable {
  var containerStopped = false
    private set
  private val container = object : StoveElasticSearchContainer(ElasticsearchTestRuntimeConfig.imageName) {
    override fun stop() {
      containerStopped = true
      if (stopFailure != null) throw stopFailure
    }
  }
  val system = ElasticsearchSystem(
    Stove(),
    ElasticsearchContext(
      container,
      ElasticsearchSystemOptions(
        cleanup = { if (cleanupFailure != null) throw cleanupFailure },
        configureExposedConfiguration = { emptyList() }
      )
    )
  )

  // Constructing a transport does not connect; this fixture never starts another container.
  private val transport = if (initializeClient) {
    RestClientTransport(RestClient.builder(HttpHost("127.0.0.1", 1)).build(), JacksonJsonpMapper())
      .also { system.esClient = ElasticsearchClient(it) }
  } else {
    null
  }

  override fun close() {
    transport?.close()
  }
}
