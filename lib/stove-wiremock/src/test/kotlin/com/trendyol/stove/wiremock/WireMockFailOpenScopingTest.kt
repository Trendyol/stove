package com.trendyol.stove.wiremock

import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import com.trendyol.stove.serialization.StoveSerde
import com.trendyol.stove.system.Stove
import com.trendyol.stove.system.stove
import com.trendyol.stove.tracing.TraceContext
import com.trendyol.stove.wiremock.WireMockSystem.Companion.server
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpRequest.BodyPublishers
import java.net.http.HttpResponse.BodyHandlers

/**
 * Fail-open scoping: journal entries are excluded from a test's view only when they are
 * provably tagged with a different test id; untagged evidence is visible to every test.
 */
class WireMockFailOpenScopingTest :
  FunSpec({
    val client = HttpClient.newBuilder().build()

    fun postTo(baseUrl: String, url: String, headers: Map<String, String> = emptyMap()): Int {
      val builder = HttpRequest
        .newBuilder(URI("$baseUrl$url"))
        .header("Content-Type", "application/json")
        .POST(BodyPublishers.ofString("{}"))
      headers.forEach { (key, value) -> builder.header(key, value) }
      return client.send(builder.build(), BodyHandlers.ofString()).statusCode()
    }

    test("unmatched request provably tagged with another test id does not fail validate") {
      stove {
        wiremock {
          postTo(
            WIREMOCK_BASE_URL,
            "/scoping/foreign-tag",
            headers = mapOf(TraceContext.STOVE_TEST_ID_HEADER to "an-entirely-different-test")
          ) shouldBe 404

          validate()
        }
      }
    }

    test("unmatched request tagged with another test id via baggage does not fail validate") {
      stove {
        wiremock {
          postTo(
            WIREMOCK_BASE_URL,
            "/scoping/foreign-baggage",
            headers = mapOf("baggage" to "stove.test.id=another%20test")
          ) shouldBe 404

          validate()
        }
      }
    }

    test("unmatched request tagged with the current test id fails validate") {
      stove {
        wiremock {
          postTo(
            WIREMOCK_BASE_URL,
            "/scoping/own-tag",
            headers = mapOf(TraceContext.STOVE_TEST_ID_HEADER to Stove.reporter().currentTestId())
          ) shouldBe 404

          val error = shouldThrow<AssertionError> { validate() }
          error.message shouldContain "/scoping/own-tag"
        }
      }
    }

    test("untagged unmatched request fails validate for every test (fail-open)") {
      lateinit var host: Stove
      stove {
        wiremock { host = this.stove }
      }

      val isolated = WireMockSystem(
        host,
        WireMockContext(
          port = 0,
          removeStubAfterRequestMatched = false,
          afterStubRemoved = { _, _ -> },
          afterRequest = { _, _ -> },
          serde = StoveSerde.jackson.anyByteArraySerde(),
          configure = { this },
          configureExposedConfiguration = { listOf() }
        )
      )
      isolated.run()
      try {
        postTo("http://localhost:${isolated.server().port()}", "/scoping/untagged") shouldBe 404

        val error = shouldThrow<AssertionError> { isolated.validate() }
        error.message shouldContain "/scoping/untagged"
      } finally {
        isolated.close()
      }
    }

    context("journal-level stub scoping") {
      fun taggedStub(testId: String) = post(urlEqualTo("/tagged"))
        .willReturn(aResponse())
        .withMetadata(mapOf(WireMockSystem.STOVE_TEST_ID_KEY to testId))
        .build()

      test("stubs registered without a test context are visible to every test") {
        val journal = WireMockCallJournal()
        val untagged = get(urlEqualTo("/untagged")).willReturn(aResponse()).build()

        journal.recordStub(untagged)

        journal.stubs("some-test") shouldContain untagged
        journal.stubs("another-test") shouldContain untagged
      }

      test("stubs tagged with a test id are visible only to that test") {
        val journal = WireMockCallJournal()
        val tagged = taggedStub("test-1")

        journal.recordStub(tagged)

        journal.stubs("test-1") shouldContain tagged
        journal.stubs("test-2") shouldNotContain tagged
      }

      test("clearing a test keeps untagged stubs visible") {
        val journal = WireMockCallJournal()
        val untagged = get(urlEqualTo("/untagged")).willReturn(aResponse()).build()
        val tagged = taggedStub("test-1")

        journal.recordStub(untagged)
        journal.recordStub(tagged)
        journal.clear("test-1")

        journal.stubs("test-1") shouldContain untagged
        journal.stubs("test-1") shouldNotContain tagged
      }
    }
  })
