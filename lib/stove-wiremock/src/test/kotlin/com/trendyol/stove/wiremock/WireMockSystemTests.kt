package com.trendyol.stove.wiremock

import arrow.core.some
import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.*
import com.trendyol.stove.system.stove
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.*
import java.net.URI
import java.net.http.*
import java.net.http.HttpRequest.BodyPublishers
import java.net.http.HttpResponse.BodyHandlers

class WireMockSystemTests :
  FunSpec({
    lateinit var wireMock: WireMockServer
    lateinit var client: HttpClient
    lateinit var reqBuilder: HttpRequest.Builder
    val url = "post-url"
    beforeSpec {
      wireMock = WireMockServer(0)
      wireMock.start()
      client = HttpClient.newBuilder().build()
      reqBuilder = HttpRequest.newBuilder(URI("http://localhost:${wireMock.port()}/$url"))
    }

    test("Single thread stubbing") {
      wireMock.stubFor(
        post("/$url")
          .withRequestBody(equalTo("request1"))
          .willReturn(
            aResponse()
              .withBody("response1")
          )
      )

      wireMock.stubFor(
        post("/$url")
          .withRequestBody(equalTo("request2"))
          .willReturn(
            aResponse()
              .withBody("response2")
          )
      )

      val request2 = reqBuilder.POST(BodyPublishers.ofString("request2")).build()
      val response2 = client.send(request2, BodyHandlers.ofString())
      response2.body() shouldBe "response2"

      val request1 = reqBuilder.POST(BodyPublishers.ofString("request1")).build()
      val response1 = client.send(request1, BodyHandlers.ofString())
      response1.body() shouldBe "response1"
    }

    test("Multi thread stubbing") {

      (1..20)
        .map { i ->
          async {
            wireMock.stubFor(
              post("/$url")
                .withRequestBody(equalTo("request$i"))
                .willReturn(
                  aResponse()
                    .withBody("response$i")
                )
            )
          }
        }.awaitAll()

      (1..20)
        .map { i ->
          async {
            val request = reqBuilder.POST(BodyPublishers.ofString("request$i")).build()
            val response = client.send(request, BodyHandlers.ofString())
            response.body() shouldBe "response$i"
          }
        }.awaitAll()
    }

    context("Response Headers") {
      val reqBuilder = HttpRequest
        .newBuilder(URI("$WIREMOCK_BASE_URL/headers"))
        .header("Content-Type", "application/json")

      val headers = mapOf("CustomHeaderKey" to "CustomHeaderValue")

      test("Stub get response with header") {
        val response = TestDto("get")
        stove {
          wiremock {
            mockGet("/headers", statusCode = 200, responseBody = response.some(), responseHeaders = headers)
          }
        }

        val request = reqBuilder.GET().build()
        val httpResponse = client.send(request, BodyHandlers.ofString())
        httpResponse.body() shouldBe "{\"name\":\"get\"}"
        httpResponse.headers().firstValue("CustomHeaderKey").get() shouldBe "CustomHeaderValue"
      }

      test("Stub post response with header") {
        val response = TestDto("post")
        stove {
          wiremock {
            mockPost("/headers", statusCode = 200, responseBody = response.some(), responseHeaders = headers)
          }
        }

        val request = reqBuilder.POST(BodyPublishers.ofString("post-response-with-header")).build()
        val httpResponse = client.send(request, BodyHandlers.ofString())
        httpResponse.body() shouldBe "{\"name\":\"post\"}"
        httpResponse.headers().firstValue("CustomHeaderKey").get() shouldBe "CustomHeaderValue"
      }

      test("Stub put response with header") {
        val response = TestDto("put")
        stove {
          wiremock {
            mockPut("/headers", statusCode = 200, responseBody = response.some(), responseHeaders = headers)
          }
        }

        val request = reqBuilder.PUT(BodyPublishers.ofString("put-response-with-header")).build()
        val httpResponse = client.send(request, BodyHandlers.ofString())
        httpResponse.body() shouldBe "{\"name\":\"put\"}"
        httpResponse.headers().firstValue("CustomHeaderKey").get() shouldBe "CustomHeaderValue"
      }

      test("Stub patch response with header") {
        val response = TestDto("patch")
        stove {
          wiremock {
            mockPatch("/headers", statusCode = 200, responseBody = response.some(), responseHeaders = headers)
          }
        }

        val request = reqBuilder.method("PATCH", BodyPublishers.ofString("patch-response-with-header")).build()
        val httpResponse = client.send(request, BodyHandlers.ofString())
        httpResponse.body() shouldBe "{\"name\":\"patch\"}"
        httpResponse.headers().firstValue("CustomHeaderKey").get() shouldBe "CustomHeaderValue"
      }
    }
  })

data class TestDto(
  val name: String
)
