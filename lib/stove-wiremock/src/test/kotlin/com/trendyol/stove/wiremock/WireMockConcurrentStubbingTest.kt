package com.trendyol.stove.wiremock

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.*
import com.trendyol.stove.testCaseConcurrency
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.concurrency.TestExecutionMode
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpRequest.BodyPublishers
import java.net.http.HttpResponse.BodyHandlers

class WireMockConcurrentStubbingTest : FunSpec({
  testExecutionMode = TestExecutionMode.LimitedConcurrency(testCaseConcurrency())
  blockingTest = true

  test("Single thread stubbing") {
    withStubbingFixture { server, client ->
      (1..2).forEach { i ->
        server.stubFor(post("/post-url").withRequestBody(equalTo("request$i")).willReturn(aResponse().withBody("response$i")))
      }

      listOf(2, 1).forEach { i ->
        val request = HttpRequest.newBuilder(URI("${server.baseUrl()}/post-url"))
          .POST(BodyPublishers.ofString("request$i"))
          .build()
        client.send(request, BodyHandlers.ofString()).body() shouldBe "response$i"
      }
    }
  }

  test("Multi thread stubbing") {
    withStubbingFixture { server, client ->
      coroutineScope {
        (1..20).map { i ->
          async(Dispatchers.IO) {
            server.stubFor(post("/post-url").withRequestBody(equalTo("request$i")).willReturn(aResponse().withBody("response$i")))
          }
        }.awaitAll()

        (1..20).map { i ->
          async(Dispatchers.IO) {
            // Builders are mutable, so each concurrent request owns its builder.
            val request = HttpRequest.newBuilder(URI("${server.baseUrl()}/post-url"))
              .POST(BodyPublishers.ofString("request$i"))
              .build()
            client.send(request, BodyHandlers.ofString()).body() shouldBe "response$i"
          }
        }.awaitAll()
      }
    }
  }
})

private suspend fun withStubbingFixture(block: suspend (WireMockServer, HttpClient) -> Unit) {
  val server = WireMockServer(0)
  try {
    server.start()
    block(server, HttpClient.newHttpClient())
  } finally {
    server.stop()
  }
}
