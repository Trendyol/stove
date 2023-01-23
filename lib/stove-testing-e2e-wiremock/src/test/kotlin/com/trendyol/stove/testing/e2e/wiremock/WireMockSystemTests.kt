package com.trendyol.stove.testing.e2e.wiremock

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.equalTo
import com.github.tomakehurst.wiremock.client.WireMock.post
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpRequest.BodyPublishers
import java.net.http.HttpResponse.BodyHandlers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext

class WireMockSystemTests : FunSpec({
    lateinit var wireMock: WireMockServer
    lateinit var client: HttpClient
    lateinit var reqBuilder: HttpRequest.Builder
    val url = "post-url"
    beforeSpec {
        wireMock = WireMockServer(9090)
        wireMock.start()
        client = HttpClient.newBuilder().build()
        reqBuilder = HttpRequest.newBuilder(URI("http://localhost:9090/$url"))
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

        withContext(Dispatchers.IO) {
            val request2 = reqBuilder.POST(BodyPublishers.ofString("request2")).build()
            val response2 = client.send(request2, BodyHandlers.ofString())
            response2.body() shouldBe "response2"

            val request1 = reqBuilder.POST(BodyPublishers.ofString("request1")).build()
            val response1 = client.send(request1, BodyHandlers.ofString())
            response1.body() shouldBe "response1"
        }
    }

    test("Multi thread stubbing") {

        (1..20).map { i ->
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

        (1..20).map { i ->
            async {
                val request = reqBuilder.POST(BodyPublishers.ofString("request$i")).build()
                val response = withContext(Dispatchers.IO) {
                    client.send(request, BodyHandlers.ofString())
                }
                response.body() shouldBe "response$i"
            }
        }.awaitAll()
    }
})
