package com.trendyol.stove.wiremock

import arrow.core.some
import com.github.tomakehurst.wiremock.client.WireMock.*
import com.github.tomakehurst.wiremock.http.RequestMethod
import com.trendyol.stove.reporting.SystemSnapshot
import com.trendyol.stove.system.Stove
import com.trendyol.stove.system.stove
import com.trendyol.stove.tracing.TraceContext
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpRequest.BodyPublishers
import java.net.http.HttpResponse.BodyHandlers

class WireMockVerificationTest :
  FunSpec({
    val client = HttpClient.newBuilder().build()

    fun request(
      url: String,
      body: String = "{}",
      headers: Map<String, String> = emptyMap()
    ): HttpRequest {
      val builder = HttpRequest
        .newBuilder(URI("$WIREMOCK_BASE_URL$url"))
        .header("Content-Type", "application/json")
        .POST(BodyPublishers.ofString(body))

      headers.forEach { (key, value) -> builder.header(key, value) }
      return builder.build()
    }

    fun get(url: String): HttpRequest =
      HttpRequest
        .newBuilder(URI("$WIREMOCK_BASE_URL$url"))
        .GET()
        .build()

    @Suppress("UNCHECKED_CAST")
    fun SystemSnapshot.listState(name: String): List<Map<String, Any>> =
      state[name] as List<Map<String, Any>>

    test("shouldHaveBeenCalled should pass for exact request body after stub removal") {
      val url = "/verification/exact-body"
      val body = mapOf("orderId" to "order-1")

      stove {
        wiremock {
          mockPost(url = url, statusCode = 200, requestBody = body.some())
        }
      }

      client.send(request(url, """{"orderId":"order-1"}"""), BodyHandlers.ofString()).statusCode() shouldBe 200

      stove {
        wiremock {
          shouldHaveBeenCalled(
            method = RequestMethod.POST,
            url = url,
            requestBody = body.some()
          )
        }
      }
    }

    test("shouldHaveBeenCalled should pass for partial nested request body") {
      val url = "/verification/partial-body"

      stove {
        wiremock {
          mockPost(url = url, statusCode = 200)
        }
      }

      val body = """{"order":{"customer":{"id":"customer-1","name":"Ada"}},"ignored":true}"""
      client.send(request(url, body), BodyHandlers.ofString()).statusCode() shouldBe 200

      stove {
        wiremock {
          shouldHaveBeenCalled(
            method = RequestMethod.POST,
            url = url,
            requestContaining = mapOf("order.customer.id" to "customer-1")
          )
        }
      }
    }

    test("shouldHaveBeenCalled should pass for headers query params and url pattern") {
      val url = "/verification/query"

      stove {
        wiremock {
          mockPostConfigure(url = url, urlPatternFn = { urlPathEqualTo(it) }) { req, _ ->
            req
              .withQueryParam("page", equalTo("1"))
              .willReturn(aResponse().withStatus(200))
          }
        }
      }

      client
        .send(
          request("$url?page=1", headers = mapOf("X-Request-Id" to "req-1")),
          BodyHandlers.ofString()
        ).statusCode() shouldBe 200

      stove {
        wiremock {
          shouldHaveBeenCalled(
            method = RequestMethod.POST,
            url = url,
            headers = mapOf("X-Request-Id" to "req-1"),
            queryParams = mapOf("page" to "1"),
            urlPatternFn = { urlPathEqualTo(it) }
          )
        }
      }
    }

    test("shouldHaveBeenCalled should pass with advanced WireMock request pattern") {
      val url = "/verification/advanced"

      stove {
        wiremock {
          mockPost(url = url, statusCode = 200)
        }
      }

      client.send(request(url, headers = mapOf("X-Mode" to "advanced")), BodyHandlers.ofString()).statusCode() shouldBe 200

      stove {
        wiremock {
          shouldHaveBeenCalled {
            postRequestedFor(urlEqualTo(url))
              .withHeader("X-Mode", equalTo("advanced"))
          }
        }
      }
    }

    test("shouldHaveBeenCalled should fail when no matching call exists") {
      val error = shouldThrow<AssertionError> {
        stove {
          wiremock {
            shouldHaveBeenCalled(method = RequestMethod.GET, url = "/verification/not-called")
          }
        }
      }

      error.message shouldContain "Expected exactly 1 requests"
    }

    test("shouldHaveBeenCalled should fail on duplicate calls by default") {
      val url = "/verification/duplicate"

      repeat(2) {
        stove {
          wiremock {
            mockGet(url = url, statusCode = 200)
          }
        }
        client.send(get(url), BodyHandlers.ofString()).statusCode() shouldBe 200
      }

      val error = shouldThrow<AssertionError> {
        stove {
          wiremock {
            shouldHaveBeenCalled(method = RequestMethod.GET, url = url)
          }
        }
      }

      error.message shouldContain "received 2"
    }

    test("shouldNotHaveBeenCalled should pass for zero calls and fail for matching calls") {
      val url = "/verification/not-called-negative"
      val calledUrl = "/verification/called-negative"

      stove {
        wiremock {
          mockGet(url = calledUrl, statusCode = 200)
          shouldNotHaveBeenCalled(method = RequestMethod.GET, url = url)
        }
      }

      client.send(get(calledUrl), BodyHandlers.ofString()).statusCode() shouldBe 200

      val error = shouldThrow<AssertionError> {
        stove {
          wiremock {
            shouldNotHaveBeenCalled(method = RequestMethod.GET, url = calledUrl)
          }
        }
      }

      error.message shouldContain "Expected exactly 0 requests"
    }

    test("callsFor should return only matching current test requests") {
      val targetUrl = "/verification/calls-for-target"
      val otherUrl = "/verification/calls-for-other"

      stove {
        wiremock {
          mockPost(url = targetUrl, statusCode = 200)
          mockPost(url = otherUrl, statusCode = 200)
        }
      }

      client.send(request(targetUrl, """{"type":"target"}"""), BodyHandlers.ofString()).statusCode() shouldBe 200
      client.send(request(otherUrl, """{"type":"other"}"""), BodyHandlers.ofString()).statusCode() shouldBe 200

      stove {
        wiremock {
          callsFor(method = RequestMethod.POST, url = targetUrl) shouldHaveSize 1
        }
      }
    }

    test("shouldHaveBeenCalled should pass with a custom count strategy") {
      val url = "/verification/custom-count"

      repeat(2) {
        stove {
          wiremock {
            mockGet(url = url, statusCode = 200)
          }
        }
        client.send(get(url), BodyHandlers.ofString()).statusCode() shouldBe 200
      }

      stove {
        wiremock {
          shouldHaveBeenCalled(
            method = RequestMethod.GET,
            url = url,
            count = moreThanOrExactly(2)
          )
        }
      }
    }

    test("snapshot should include registered received and served state after stub removal") {
      val url = "/verification/snapshot-served"

      stove {
        wiremock {
          mockPost(
            url = url,
            statusCode = 201,
            responseBody = mapOf("created" to true).some(),
            responseHeaders = mapOf("X-Served-By" to "stove")
          )
        }
      }

      client.send(request(url, """{"orderId":"snapshot-1"}"""), BodyHandlers.ofString()).statusCode() shouldBe 201

      lateinit var snapshot: SystemSnapshot
      stove {
        wiremock {
          snapshot = snapshot()
        }
      }

      val registeredStubs = snapshot.listState("registeredStubs")
      registeredStubs shouldHaveSize 1
      registeredStubs.first()["active"] shouldBe false
      registeredStubs.first()["method"] shouldBe "POST"
      registeredStubs.first()["url"] shouldBe url
      registeredStubs.first()["status"] shouldBe 201

      val activeStubs = snapshot.listState("activeStubs")
      activeStubs shouldHaveSize 0

      val receivedRequests = snapshot.listState("receivedRequests")
      receivedRequests shouldHaveSize 1
      receivedRequests.first()["method"] shouldBe "POST"
      receivedRequests.first()["url"] shouldBe url
      receivedRequests.first()["body"].toString() shouldContain "snapshot-1"

      val servedRequests = snapshot.listState("servedRequests")
      servedRequests shouldHaveSize 1
      val servedResponse = servedRequests.first()["response"] as Map<*, *>
      servedResponse["status"] shouldBe 201
      servedResponse["body"].toString() shouldContain "created"

      snapshot.summary shouldContain "Registered stubs (this test): 1 (active: 0)"
      snapshot.summary shouldContain "Received requests (this test): 1"
      snapshot.summary shouldContain "Served requests (this test): 1 (matched: 1)"
    }

    test("snapshot should include unmatched requests scoped by Stove test header") {
      val testId = Stove.reporter().currentTestId()
      val url = "/verification/snapshot-unmatched"

      client
        .send(
          request(url, headers = mapOf(TraceContext.STOVE_TEST_ID_HEADER to testId)),
          BodyHandlers.ofString()
        ).statusCode() shouldBe 404

      lateinit var snapshot: SystemSnapshot
      stove {
        wiremock {
          snapshot = snapshot()
        }
      }

      val unmatchedRequests = snapshot.listState("unmatchedRequests")
      unmatchedRequests shouldHaveSize 1
      unmatchedRequests.first()["matched"] shouldBe false
      unmatchedRequests.first()["method"] shouldBe "POST"
      unmatchedRequests.first()["url"] shouldBe url

      val servedRequests = snapshot.listState("servedRequests")
      servedRequests shouldHaveSize 1
      val servedResponse = servedRequests.first()["response"] as Map<*, *>
      servedResponse["status"] shouldBe 404

      snapshot.summary shouldContain "Unmatched requests: 1"
    }
  })
