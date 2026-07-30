package com.trendyol.stove.wiremock

import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.containing
import com.github.tomakehurst.wiremock.client.WireMock.exactly
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.matching
import com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo
import com.github.tomakehurst.wiremock.http.RequestMethod
import com.trendyol.stove.system.Stove
import com.trendyol.stove.system.stove
import com.trendyol.stove.wiremock.WireMockSystem.Companion.server
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpRequest.BodyPublishers
import java.net.http.HttpResponse.BodyHandlers
import java.net.http.HttpTimeoutException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.microseconds
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
          mockGet("/dsl/json-null") {
            respond {
              delay = 1.milliseconds
              header("Content-Type", "application/vnd.stove+json")
              jsonNull()
            }
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

      val jsonNull = getResponse("/dsl/json-null")
      jsonNull.body().decodeToString() shouldBe "null"
      jsonNull.headers().firstValue("Content-Type").orElseThrow() shouldBe "application/vnd.stove+json"

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

    test("structured verbs and targets compile to matching WireMock mappings") {
      stove {
        wiremock {
          mockPut("/dsl/put") {
            respond { text("put") }
          }
          mockPatch("/dsl/patch") {
            respond { text("patch") }
          }
          mockHead("/dsl/head") {
            respond { empty() }
          }
          stub(RequestMethod.GET, exactUrl("/dsl/exact?mode=full")) {
            respond { text("exact") }
          }
          stub(RequestMethod.GET, pathRegex("/dsl/items/[0-9]+")) {
            respond { text("regex") }
          }
        }
      }

      fun send(path: String, method: String = "GET") = client.send(
        HttpRequest
          .newBuilder(URI("$WIREMOCK_BASE_URL$path"))
          .method(method, BodyPublishers.noBody())
          .build(),
        BodyHandlers.ofString()
      )

      send("/dsl/put", "PUT").body() shouldBe "put"
      send("/dsl/patch", "PATCH").body() shouldBe "patch"
      send("/dsl/head", "HEAD").statusCode() shouldBe 200
      send("/dsl/exact?mode=full").body() shouldBe "exact"
      send("/dsl/items/42").body() shouldBe "regex"
    }

    test("request matcher overloads are shared by stubbing verification and call lookup") {
      val url = "/dsl/matchers"
      val expectedBody = mapOf(
        "customer" to mapOf("id" to "customer-1"),
        "lines" to listOf("first", "second")
      )

      stove {
        wiremock {
          mockPost(url) {
            request {
              header("X-Mode", containing("live"))
              query("filter", matching("active|pending"))
              jsonEqualTo(expectedBody, ignoreArrayOrder = false, ignoreExtraElements = true)
              jsonPath("$.customer.id", containing("customer"))
              jsonPath("$.customer.id", "customer-1")
            }
            respond { text("matched") }
          }
          mockPost("$url/default-json") {
            request {
              jsonEqualTo(expectedBody)
            }
            respond { text("default-json") }
          }
        }
      }

      val response = client.send(
        HttpRequest
          .newBuilder(URI("$WIREMOCK_BASE_URL$url?filter=active"))
          .header("X-Mode", "live-traffic")
          .POST(
            BodyPublishers.ofString(
              """{"customer":{"id":"customer-1"},"lines":["first","second"],"extra":true}"""
            )
          )
          .build(),
        BodyHandlers.ofString()
      )
      response.body() shouldBe "matched"

      val defaultJsonResponse = client.send(
        HttpRequest
          .newBuilder(URI("$WIREMOCK_BASE_URL$url/default-json"))
          .POST(
            BodyPublishers.ofString(
              """{"customer":{"id":"customer-1"},"lines":["first","second"]}"""
            )
          )
          .build(),
        BodyHandlers.ofString()
      )
      defaultJsonResponse.body() shouldBe "default-json"

      stove {
        wiremock {
          shouldHaveBeenCalled(RequestMethod.POST, path(url)) {
            header("X-Mode", containing("live"))
            query("filter", matching("active|pending"))
          }
          callsFor(RequestMethod.POST, path(url)) {
            jsonPath("$.customer.id", "customer-1")
          } shouldHaveSize 1
          shouldNotHaveBeenCalled(RequestMethod.GET, exactUrl("$url?filter=active"))
        }
      }
    }

    test("binary response snapshots bytes and retains value semantics") {
      val source = byteArrayOf(1, 2, 3)
      val response = ResponseDsl(ResponseModel()).apply {
        bytes(source, "application/octet-stream")
      }.build()

      source[0] = 9
      val body = response.body as ResponseBody.Bytes
      body.value.toList() shouldBe listOf<Byte>(1, 2, 3)
      body shouldBe body
      body shouldBe ResponseBody.Bytes(byteArrayOf(1, 2, 3), "application/octet-stream")
      body shouldNotBe ResponseBody.Bytes(byteArrayOf(1, 2, 3), "application/example")
      body shouldNotBe "not bytes"
      body.hashCode() shouldBe ResponseBody.Bytes(
        byteArrayOf(1, 2, 3),
        "application/octet-stream"
      ).hashCode()
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

    test("request DSL rejects duplicate header and query names") {
      stove {
        wiremock {
          val headerError = shouldThrow<IllegalArgumentException> {
            request(RequestMethod.GET, path("/dsl/duplicate-header")) {
              header("X-Tenant", "nl")
              header("x-tenant", containing("tr"))
            }
          }
          headerError.message shouldBe "Header 'x-tenant' has already been configured"

          val queryError = shouldThrow<IllegalArgumentException> {
            request(RequestMethod.GET, path("/dsl/duplicate-query")) {
              query("page", "1")
              query("page", containing("2"))
            }
          }
          queryError.message shouldBe "Query parameter 'page' has already been configured"
        }
      }
    }

    test("structured DSL rejects invalid names and response values") {
      stove {
        wiremock {
          shouldThrow<IllegalArgumentException> {
            mockGet("/dsl/blank-name", name = " ") {
              respond { empty() }
            }
          }.message shouldBe "Stub name must not be blank"

          shouldThrow<IllegalArgumentException> {
            rawStub(" ") {
              get(urlPathEqualTo("/dsl/blank-raw-name"))
                .willReturn(aResponse())
            }
          }.message shouldBe "Raw stub name must not be blank"

          shouldThrow<IllegalArgumentException> {
            request(RequestMethod.GET, path("/dsl/blank-header")) {
              header(" ", "value")
            }
          }.message shouldBe "Header name must not be blank"

          shouldThrow<IllegalArgumentException> {
            request(RequestMethod.GET, path("/dsl/blank-query")) {
              query(" ", "value")
            }
          }.message shouldBe "Query parameter name must not be blank"

          shouldThrow<IllegalArgumentException> {
            request(RequestMethod.POST, path("/dsl/blank-json-field")) {
              jsonField(" ", "value")
            }
          }.message shouldBe "JSON field path must not be blank"

          shouldThrow<IllegalArgumentException> {
            request(RequestMethod.POST, path("/dsl/blank-json-path")) {
              jsonPath(" ", "value")
            }
          }.message shouldBe "JSONPath expression must not be blank"

          shouldThrow<IllegalArgumentException> {
            mockGet("/dsl/invalid-status") {
              respond { status = 99 }
            }
          }.message shouldBe "WireMock response status must be between 100 and 599"

          shouldThrow<IllegalArgumentException> {
            mockGet("/dsl/blank-response-header") {
              respond { header(" ", "value") }
            }
          }.message shouldBe "Response header name must not be blank"

          shouldThrow<IllegalArgumentException> {
            mockGet("/dsl/blank-binary-content-type") {
              respond { bytes(byteArrayOf(1), " ") }
            }
          }.message shouldBe "Binary response content type must not be blank"

          shouldThrow<IllegalStateException> {
            mockGet("/dsl/duplicate-body") {
              respond {
                text("first")
                empty()
              }
            }
          }.message shouldBe "A response body has already been configured"
        }
      }
    }

    test("structured DSL rejects ambiguous block and behaviour ordering") {
      stove {
        wiremock {
          shouldThrow<IllegalStateException> {
            mockGet("/dsl/duplicate-request") {
              request {}
              request {}
            }
          }.message shouldBe "A request block has already been configured"

          shouldThrow<IllegalStateException> {
            mockGet("/dsl/duplicate-response") {
              respond { text("first") }
              respond { text("second") }
            }
          }.message shouldBe "A response block has already been configured"

          shouldThrow<IllegalStateException> {
            mockGet("/dsl/duplicate-behaviour") {
              behaviour {
                timeout(1.milliseconds)
                thenAlways { empty() }
              }
              behaviour {}
            }
          }.message shouldBe "A behaviour block has already been configured"

          shouldThrow<IllegalStateException> {
            mockGet("/dsl/response-after-behaviour") {
              behaviour {
                timeout(1.milliseconds)
                thenAlways { empty() }
              }
              respond { empty() }
            }
          }.message shouldBe "A behaviour block has already been configured"

          shouldThrow<IllegalArgumentException> {
            mockGet("/dsl/no-transient-response") {
              behaviour {
                thenAlways { empty() }
              }
            }
          }.message shouldBe "A behaviour must contain at least one response before thenAlways"

          shouldThrow<IllegalStateException> {
            mockGet("/dsl/respond-after-terminal") {
              behaviour {
                timeout(1.milliseconds)
                thenAlways { empty() }
                respond { empty() }
              }
            }
          }.message shouldBe "No responses can be added after thenAlways"

          shouldThrow<IllegalStateException> {
            mockGet("/dsl/duplicate-terminal") {
              behaviour {
                timeout(1.milliseconds)
                thenAlways { empty() }
                thenAlways { empty() }
              }
            }
          }.message shouldBe "thenAlways has already been configured"

          shouldThrow<IllegalArgumentException> {
            mockGet("/dsl/zero-timeout") {
              behaviour {
                timeout(Duration.ZERO)
                thenAlways { empty() }
              }
            }
          }.message shouldBe "Behaviour timeout must be finite and positive"

          shouldThrow<IllegalArgumentException> {
            mockGet("/dsl/sub-millisecond-timeout") {
              behaviour {
                timeout(500.microseconds)
                thenAlways { empty() }
              }
            }
          }.message shouldBe "Behaviour timeout must be at least 1 millisecond"
        }
      }
    }
  })
