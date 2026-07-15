package com.trendyol.stove.wiremock

import arrow.core.some
import com.trendyol.stove.interactions.InteractionAttribution
import com.trendyol.stove.interactions.MockInteraction
import com.trendyol.stove.system.Stove
import com.trendyol.stove.system.stove
import com.trendyol.stove.tracing.TraceContext
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.delay
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpRequest.BodyPublishers
import java.net.http.HttpResponse.BodyHandlers
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Every exchange that reaches the mock is emitted as an interaction — matched or not —
 * with proven-only attribution.
 */
class WireMockInteractionTest :
  FunSpec({
    val client = HttpClient.newBuilder().build()

    suspend fun awaitUntil(timeoutMs: Long = 5_000, condition: () -> Boolean) {
      val deadline = System.currentTimeMillis() + timeoutMs
      while (!condition() && System.currentTimeMillis() < deadline) delay(50)
      condition().shouldBeTrue()
    }

    test("matched and unmatched exchanges are emitted with proven attribution") {
      val interactions = CopyOnWriteArrayList<MockInteraction>()
      val listener = com.trendyol.stove.interactions
        .MockInteractionListener { interactions.add(it) }

      stove {
        wiremock {
          addInteractionListener(listener)
          try {
            mockGet(
              url = "/interactions/orders",
              statusCode = 200,
              responseBody = mapOf("ok" to true).some()
            )

            // Matched call with no headers at all: attribution comes from the stub.
            client
              .send(
                HttpRequest.newBuilder(URI("$WIREMOCK_BASE_URL/interactions/orders")).GET().build(),
                BodyHandlers.ofString()
              ).statusCode() shouldBe 200

            // Unmatched call carrying the test-id header: attribution comes from the traffic.
            client
              .send(
                HttpRequest
                  .newBuilder(URI("$WIREMOCK_BASE_URL/interactions/nowhere"))
                  .header("Content-Type", "application/json")
                  .header(TraceContext.STOVE_TEST_ID_HEADER, Stove.reporter().currentTestId())
                  .POST(BodyPublishers.ofString("""{"probe":true}"""))
                  .build(),
                BodyHandlers.ofString()
              ).statusCode() shouldBe 404

            awaitUntil { interactions.count { it.target.startsWith("/interactions/") } >= 2 }

            val matched = interactions.single { it.target == "/interactions/orders" }
            matched.matched shouldBe true
            matched.attribution shouldBe InteractionAttribution.PROVEN_STUB
            matched.testId shouldBe Stove.reporter().currentTestId()
            matched.protocol shouldBe MockInteraction.Protocol.HTTP
            matched.method shouldBe "GET"
            matched.status shouldBe "200"
            matched.stubId.shouldNotBeNull()
            matched.latencyMs.shouldNotBeNull()

            val unmatched = interactions.single { it.target == "/interactions/nowhere" }
            unmatched.matched shouldBe false
            unmatched.attribution shouldBe InteractionAttribution.PROVEN_HEADER
            unmatched.testId shouldBe Stove.reporter().currentTestId()
            unmatched.status shouldBe "404"
            unmatched.nearMisses.shouldNotBeEmpty()
            unmatched.requestBody shouldBe """{"probe":true}"""
          } finally {
            removeInteractionListener(listener)
          }
        }
      }
    }
  })
