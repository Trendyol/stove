package com.trendyol.stove.wiremock

import com.github.tomakehurst.wiremock.client.WireMock.*
import com.github.tomakehurst.wiremock.matching.ContainsPattern
import com.trendyol.stove.system.stove
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.net.URI
import java.net.http.*
import java.net.http.HttpRequest.BodyPublishers
import java.net.http.HttpResponse.BodyHandlers

class WireMockDeletionTest :
  FunSpec({
    /*
     * Check [WireMockContext.removeStubAfterRequestMatched]
     */
    test("Remove stub from wiremock when request is matched") {
      val reqBody = "{\"req\": 1}"
      val responseBody = "{\"res\": 1}"
      stove {
        wiremock {
          mockPostConfigure("/post-url") { req, _ ->
            req
              .withRequestBody(equalToJson(reqBody))
              .withHeader("Content-Type", ContainsPattern("application/json"))
              .willReturn(
                aResponse()
                  .withBody(responseBody)
                  .withStatus(200)
                  .withHeader("Content-Type", "application/json; charset=UTF-8")
              )
          }
        }
      }

      val client = HttpClient.newBuilder().build()
      val reqBuilder = HttpRequest
        .newBuilder(URI("http://localhost:9098/post-url"))
        .header("Content-Type", "application/json")

      val request = reqBuilder.POST(BodyPublishers.ofString(reqBody)).build()
      val response = client.send(request, BodyHandlers.ofString())
      response.body() shouldBe responseBody

      val request2 = reqBuilder.POST(BodyPublishers.ofString(reqBody)).build()
      val response2 = client.send(request2, BodyHandlers.ofString())
      response2.statusCode() shouldBe 404
    }

    /*
     * Check [WireMockContext.removeStubAfterRequestMatched]
     */
    test("Removes the stub after request completes, and can be added again") {
      val reqBody = "{\"req\": 1}"
      val responseBody = "{\"res\": 1}"
      val url = "/post-url-2"
      stove {
        wiremock {
          mockPostConfigure(url) { req, _ ->
            req
              .withRequestBody(equalToJson(reqBody))
              .withHeader("Content-Type", ContainsPattern("application/json"))
              .willReturn(
                aResponse()
                  .withBody(responseBody)
                  .withStatus(200)
                  .withHeader("Content-Type", "application/json; charset=UTF-8")
              )
          }
        }
      }

      val client = HttpClient.newBuilder().build()
      val reqBuilder = HttpRequest
        .newBuilder(URI("http://localhost:9098$url"))
        .header("Content-Type", "application/json")

      val request = reqBuilder.POST(BodyPublishers.ofString(reqBody)).build()
      val response = client.send(request, BodyHandlers.ofString())
      response.body() shouldBe responseBody

      stove {
        wiremock {
          mockPostConfigure(url) { req, _ ->
            req
              .withRequestBody(equalToJson(reqBody))
              .withHeader("Content-Type", ContainsPattern("application/json"))
              .willReturn(
                aResponse()
                  .withBody(responseBody)
                  .withStatus(200)
                  .withHeader("Content-Type", "application/json; charset=UTF-8")
              )
          }
        }
      }

      val request2 = reqBuilder.POST(BodyPublishers.ofString(reqBody)).build()
      val response2 = client.send(request2, BodyHandlers.ofString())
      response2.body() shouldBe responseBody
    }
  })
