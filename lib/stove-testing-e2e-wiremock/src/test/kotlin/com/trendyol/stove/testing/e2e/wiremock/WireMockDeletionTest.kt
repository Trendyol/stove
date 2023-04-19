package com.trendyol.stove.testing.e2e.wiremock

import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.equalToJson
import com.github.tomakehurst.wiremock.matching.ContainsPattern
import com.trendyol.stove.testing.e2e.system.TestSystem
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpRequest.BodyPublishers
import java.net.http.HttpResponse.BodyHandlers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class WireMockDeletionTest : FunSpec({
    /**
     * Check [WireMockContext.removeStubAfterRequestMatched]
     */
    test("Remove stub from wiremock when request is matched") {
        val reqBody = "{\"req\": 1}"
        val responseBody = "{\"res\": 1}"
        TestSystem.validate {
            wiremock {
                mockPostConfigure("/post-url") { req, _ ->
                    req.withRequestBody(equalToJson(reqBody))
                        .withHeader("Content-Type", ContainsPattern("application/json"))
                        .willReturn(
                            aResponse().withBody(responseBody).withStatus(200)
                                .withHeader("Content-Type", "application/json; charset=UTF-8")
                        )
                }
            }
        }

        val client = HttpClient.newBuilder().build()
        val reqBuilder = HttpRequest.newBuilder(URI("http://localhost:9098/post-url"))
            .header("Content-Type", "application/json")

        withContext(Dispatchers.IO) {
            val request = reqBuilder.POST(BodyPublishers.ofString(reqBody)).build()
            val response = client.send(request, BodyHandlers.ofString())
            response.body() shouldBe responseBody

            val request2 = reqBuilder.POST(BodyPublishers.ofString(reqBody)).build()
            val response2 = client.send(request2, BodyHandlers.ofString())
            response2.statusCode() shouldBe 404
        }
    }

    /**
     * Check [WireMockContext.removeStubAfterRequestMatched]
     */
    test("Removes the stub after request completes, and can be added again") {
        val reqBody = "{\"req\": 1}"
        val responseBody = "{\"res\": 1}"
        val url = "/post-url-2"
        TestSystem.validate {
            wiremock {
                mockPostConfigure(url) { req, _ ->
                    req.withRequestBody(equalToJson(reqBody))
                        .withHeader("Content-Type", ContainsPattern("application/json"))
                        .willReturn(
                            aResponse().withBody(responseBody).withStatus(200)
                                .withHeader("Content-Type", "application/json; charset=UTF-8")
                        )
                }
            }
        }

        val client = HttpClient.newBuilder().build()
        val reqBuilder = HttpRequest.newBuilder(URI("http://localhost:9098$url"))
            .header("Content-Type", "application/json")

        withContext(Dispatchers.IO) {
            val request = reqBuilder.POST(BodyPublishers.ofString(reqBody)).build()
            val response = client.send(request, BodyHandlers.ofString())
            response.body() shouldBe responseBody
        }

        TestSystem.validate {
            wiremock {
                mockPostConfigure(url) { req, _ ->
                    req
                        .withRequestBody(equalToJson(reqBody))
                        .withHeader("Content-Type", ContainsPattern("application/json"))
                        .willReturn(
                            aResponse().withBody(responseBody).withStatus(200)
                                .withHeader("Content-Type", "application/json; charset=UTF-8")
                        )
                }
            }
        }

        withContext(Dispatchers.IO) {
            val request = reqBuilder.POST(BodyPublishers.ofString(reqBody)).build()
            val response = client.send(request, BodyHandlers.ofString())
            response.body() shouldBe responseBody
        }
    }
})
