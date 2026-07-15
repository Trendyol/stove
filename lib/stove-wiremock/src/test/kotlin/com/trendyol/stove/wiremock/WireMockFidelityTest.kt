package com.trendyol.stove.wiremock

import arrow.core.some
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.http.Fault
import com.github.tomakehurst.wiremock.http.RequestMethod
import com.trendyol.stove.system.Stove
import com.trendyol.stove.system.stove
import com.trendyol.stove.tracing.TraceContext
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.longs.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpRequest.BodyPublishers
import java.net.http.HttpResponse
import java.net.http.HttpResponse.BodyHandlers
import kotlin.time.Duration.Companion.milliseconds

/**
 * Fidelity features: network faults, response latency, retry journeys, and
 * request-computed dynamic responses.
 */
class WireMockFidelityTest :
  FunSpec({
    val client = HttpClient.newBuilder().build()

    fun request(url: String, body: String = "{}"): HttpRequest = HttpRequest
      .newBuilder(URI("$WIREMOCK_BASE_URL$url"))
      .header("Content-Type", "application/json")
      .header(TraceContext.STOVE_TEST_ID_HEADER, Stove.reporter().currentTestId())
      .POST(BodyPublishers.ofString(body))
      .build()

    fun get(url: String): HttpRequest = HttpRequest
      .newBuilder(URI("$WIREMOCK_BASE_URL$url"))
      .header(TraceContext.STOVE_TEST_ID_HEADER, Stove.reporter().currentTestId())
      .GET()
      .build()

    fun send(req: HttpRequest): HttpResponse<String> = client.send(req, BodyHandlers.ofString())

    test("mockFault surfaces as a connection-level failure to the client") {
      stove {
        wiremock {
          mockFault(RequestMethod.GET, "/fidelity/fault", Fault.CONNECTION_RESET_BY_PEER)
        }
      }

      shouldThrow<IOException> { send(get("/fidelity/fault")) }
    }

    test("delay parameter holds the response for at least the configured duration") {
      stove {
        wiremock {
          mockGet(
            url = "/fidelity/slow",
            statusCode = 200,
            responseBody = mapOf("ok" to true).some(),
            delay = 300.milliseconds
          )
        }
      }

      val start = System.nanoTime()
      send(get("/fidelity/slow")).statusCode() shouldBe 200
      val elapsedMs = (System.nanoTime() - start) / 1_000_000
      elapsedMs shouldBeGreaterThanOrEqual 300
    }

    test("failsTimes then thenSucceeds models a retry journey") {
      stove {
        wiremock {
          behaviourFor("/fidelity/retry", ::post) { _ ->
            failsTimes(2, withStatus = 503)
            thenSucceeds {
              aResponse().withStatus(200).withBody("""{"recovered":true}""")
            }
          }
        }
      }

      send(request("/fidelity/retry")).statusCode() shouldBe 503
      send(request("/fidelity/retry")).statusCode() shouldBe 503
      val recovered = send(request("/fidelity/retry"))
      recovered.statusCode() shouldBe 200
      recovered.body() shouldContain "recovered"
    }

    test("mockDynamic computes the response from the received request") {
      stove {
        wiremock {
          mockDynamic(RequestMethod.POST, "/fidelity/echo") { req, _ ->
            aResponse()
              .withStatus(201)
              .withHeader("Content-Type", "application/json")
              .withBody("""{"echoed":${req.bodyAsString}}""")
          }
        }
      }

      val response = send(request("/fidelity/echo", """{"orderId":42}"""))
      response.statusCode() shouldBe 201
      response.body() shouldBe """{"echoed":{"orderId":42}}"""
    }
  })
