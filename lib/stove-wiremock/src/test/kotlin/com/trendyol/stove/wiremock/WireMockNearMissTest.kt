package com.trendyol.stove.wiremock

import arrow.core.some
import com.github.tomakehurst.wiremock.http.RequestMethod
import com.trendyol.stove.system.Stove
import com.trendyol.stove.system.stove
import com.trendyol.stove.tracing.TraceContext
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpRequest.BodyPublishers
import java.net.http.HttpResponse.BodyHandlers

/**
 * Failures explain why nothing matched: unmatched requests are diffed against the
 * test's closest stubs, and empty verifications against the test's received requests.
 */
class WireMockNearMissTest :
  FunSpec({
    val client = HttpClient.newBuilder().build()

    fun post(url: String, body: String): Int {
      val request = HttpRequest
        .newBuilder(URI("$WIREMOCK_BASE_URL$url"))
        .header("Content-Type", "application/json")
        .header(TraceContext.STOVE_TEST_ID_HEADER, Stove.reporter().currentTestId())
        .POST(BodyPublishers.ofString(body))
        .build()
      return client.send(request, BodyHandlers.ofString()).statusCode()
    }

    test("validate failure diffs the unmatched request against the closest stub") {
      stove {
        wiremock {
          mockPost(
            url = "/near-miss/orders",
            statusCode = 200,
            requestBody = mapOf("amount" to 100).some()
          )

          post("/near-miss/orders", """{"amount": 99}""") shouldBe 404

          val error = shouldThrow<AssertionError> { validate() }
          error.message shouldContain "Closest stubs:"
          error.message shouldContain "/near-miss/orders"
          error.message shouldContain "100"
          error.message shouldContain "99"
        }
      }
    }

    test("verification failure with zero matches shows the closest received requests") {
      stove {
        wiremock {
          mockPost(url = "/near-miss/called", statusCode = 200)

          post("/near-miss/called", "{}") shouldBe 200

          val error = shouldThrow<AssertionError> {
            shouldHaveBeenCalled(method = RequestMethod.POST, url = "/near-miss/not-called")
          }
          error.message shouldContain "Closest received requests:"
          error.message shouldContain "/near-miss/called"
        }
      }
    }
  })
