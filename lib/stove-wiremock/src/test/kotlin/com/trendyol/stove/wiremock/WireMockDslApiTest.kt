@file:OptIn(com.trendyol.stove.ExperimentalStoveApi::class)

package com.trendyol.stove.wiremock

import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.exactly
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo
import com.github.tomakehurst.wiremock.http.RequestMethod
import com.trendyol.stove.system.Stove
import com.trendyol.stove.system.stove
import com.trendyol.stove.wiremock.WireMockSystem.Companion.server
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpRequest.BodyPublishers
import java.net.http.HttpResponse.BodyHandlers
import java.net.http.HttpTimeoutException
import kotlin.time.Duration.Companion.milliseconds
import java.time.Duration as JavaDuration

class WireMockDslApiTest :
  FunSpec({
    val client = HttpClient.newHttpClient()

    test("verb DSL matches a request and builds a JSON response") {
      val url = "/dsl/payments"

      stove {
        wiremock {
          mockPost(url, name = "create payment") {
            request {
              header("X-Tenant", "nl")
              query("dryRun", "false")
              contentTypeJson()
              jsonField("customer.id", "customer-1")
            }
            respond {
              status = 201
              header("X-Service", "payments")
              json(mapOf("paymentId" to "payment-1"))
            }
          }
        }
      }

      val response = client.send(
        HttpRequest
          .newBuilder(URI("$WIREMOCK_BASE_URL$url?dryRun=false"))
          .header("X-Tenant", "nl")
          .header("Content-Type", "application/json; charset=UTF-8")
          .POST(BodyPublishers.ofString("""{"customer":{"id":"customer-1"},"extra":true}"""))
          .build(),
        BodyHandlers.ofString()
      )

      response.statusCode() shouldBe 201
      response.body() shouldBe """{"paymentId":"payment-1"}"""
      response.headers().firstValue("X-Service").orElseThrow() shouldBe "payments"
      response.headers().firstValue("Content-Type").orElseThrow() shouldBe "application/json"

      stove {
        wiremock {
          shouldHaveBeenCalled(
            method = RequestMethod.POST,
            target = path(url),
            count = exactly(1)
          ) {
            header("X-Tenant", "nl")
            query("dryRun", "false")
            contentTypeJson()
            jsonField("customer.id", "customer-1")
          }
        }
      }
    }

    test("reusable request drives stubbing verification and call lookup") {
      val url = "/dsl/reusable"

      stove {
        wiremock {
          val request = request(RequestMethod.POST, path(url)) {
            header("X-Tenant", "nl")
            jsonField("customer.id", "customer-1")
          }

          stub(request, name = "reusable payment request") {
            respond {
              status = 202
              text("accepted")
            }
          }

          val response = client.send(
            HttpRequest
              .newBuilder(URI("$WIREMOCK_BASE_URL$url?ignored=true"))
              .header("X-Tenant", "nl")
              .POST(BodyPublishers.ofString("""{"customer":{"id":"customer-1"}}"""))
              .build(),
            BodyHandlers.ofString()
          )

          response.statusCode() shouldBe 202
          response.body() shouldBe "accepted"
          shouldHaveBeenCalled(request)
          callsFor(request) shouldHaveSize 1
        }
      }
    }

    test("structured and raw stubs infer names unless a description is provided") {
      val structuredUrl = "/dsl/inferred-structured"
      val rawUrl = "/dsl/inferred-raw"
      val describedUrl = "/dsl/described-raw"

      stove {
        wiremock {
          mockGet(structuredUrl) {
            respond { text("structured") }
          }
          rawStub {
            get(urlPathEqualTo(rawUrl))
              .willReturn(aResponse().withBody("raw"))
          }
          rawStub("health fallback") {
            get(urlPathEqualTo(describedUrl))
              .willReturn(aResponse().withBody("described"))
          }

          val stubs = server().stubMappings.associateBy { it.request.urlPath ?: it.request.url }
          stubs.getValue(structuredUrl).name shouldBe "GET $structuredUrl"
          stubs.getValue(rawUrl).name shouldBe "GET $rawUrl"
          stubs.getValue(describedUrl).name shouldBe "health fallback"
          stubs.values.forEach {
            it.metadata.getString(WireMockSystem.STOVE_TEST_ID_KEY) shouldBe Stove.reporter().currentTestId()
          }
        }
      }
    }

    test("response body formats set intentional content types") {
      stove {
        wiremock {
          mockGet("/dsl/text") {
            respond { text("OK") }
          }
          mockGet("/dsl/raw-json") {
            respond { rawJson("""{"status":"ok"}""") }
          }
          mockGet("/dsl/binary") {
            respond { bytes(byteArrayOf(1, 2, 3), "application/octet-stream") }
          }
          mockDelete("/dsl/empty") {
            respond {
              status = 204
              empty()
            }
          }
        }
      }

      fun getResponse(path: String) = client.send(
        HttpRequest.newBuilder(URI("$WIREMOCK_BASE_URL$path")).GET().build(),
        BodyHandlers.ofByteArray()
      )

      val text = getResponse("/dsl/text")
      text.body().decodeToString() shouldBe "OK"
      text.headers().firstValue("Content-Type").orElseThrow() shouldBe "text/plain"

      val json = getResponse("/dsl/raw-json")
      json.body().decodeToString() shouldBe """{"status":"ok"}"""
      json.headers().firstValue("Content-Type").orElseThrow() shouldBe "application/json"

      val binary = getResponse("/dsl/binary")
      binary.body().toList() shouldBe listOf<Byte>(1, 2, 3)
      binary.headers().firstValue("Content-Type").orElseThrow() shouldBe "application/octet-stream"

      val empty = client.send(
        HttpRequest
          .newBuilder(URI("$WIREMOCK_BASE_URL/dsl/empty"))
          .DELETE()
          .build(),
        BodyHandlers.ofString()
      )
      empty.statusCode() shouldBe 204
      empty.body() shouldBe ""
      empty.headers().firstValue("Content-Type").isEmpty shouldBe true
    }

    test("behaviour times out twice and then always succeeds") {
      val url = "/dsl/eventual-success"

      stove {
        wiremock {
          mockGet(url) {
            behaviour {
              repeat(2) {
                timeout(1_000.milliseconds)
              }
              thenAlways {
                status = 200
                json(mapOf("status" to "ok"))
              }
            }
          }
        }
      }

      val request = HttpRequest
        .newBuilder(URI("$WIREMOCK_BASE_URL$url"))
        .timeout(JavaDuration.ofMillis(300))
        .GET()
        .build()

      repeat(2) {
        shouldThrow<HttpTimeoutException> {
          client.send(request, BodyHandlers.ofString())
        }
      }

      repeat(2) {
        val response = client.send(request, BodyHandlers.ofString())
        response.statusCode() shouldBe 200
        response.body() shouldBe """{"status":"ok"}"""
      }
    }

    test("behaviour supports transient responses before the persistent response") {
      val url = "/dsl/retry-status"

      stove {
        wiremock {
          mockGet(url) {
            behaviour {
              repeat(2) {
                respond {
                  status = 503
                  text("retry")
                }
              }
              thenAlways {
                status = 200
                text("recovered")
              }
            }
          }
        }
      }

      val request = HttpRequest.newBuilder(URI("$WIREMOCK_BASE_URL$url")).GET().build()
      repeat(2) {
        val response = client.send(request, BodyHandlers.ofString())
        response.statusCode() shouldBe 503
        response.body() shouldBe "retry"
      }
      repeat(2) {
        val response = client.send(request, BodyHandlers.ofString())
        response.statusCode() shouldBe 200
        response.body() shouldBe "recovered"
      }
    }

    test("respond and behaviour are mutually exclusive") {
      stove {
        wiremock {
          val error = shouldThrow<IllegalStateException> {
            mockGet("/dsl/conflicting-response-plans") {
              respond { text("static") }
              behaviour {
                timeout(100.milliseconds)
                thenAlways { text("eventual") }
              }
            }
          }

          error.message shouldBe "A response block has already been configured"
        }
      }
    }

    test("behaviour requires a persistent terminal response") {
      stove {
        wiremock {
          val error = shouldThrow<IllegalStateException> {
            mockGet("/dsl/incomplete-behaviour") {
              behaviour {
                timeout(100.milliseconds)
              }
            }
          }

          error.message shouldBe "A behaviour must end with thenAlways"
        }
      }
    }
  })
