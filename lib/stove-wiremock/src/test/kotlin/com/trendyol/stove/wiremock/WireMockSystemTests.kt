package com.trendyol.stove.wiremock

import arrow.core.some
import com.trendyol.stove.system.stove
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.net.URI
import java.net.http.*
import java.net.http.HttpRequest.BodyPublishers
import java.net.http.HttpResponse.BodyHandlers

class WireMockSystemTests :
  FunSpec({
    val client = HttpClient.newBuilder().build()

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
