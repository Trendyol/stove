package com.trendyol.stove.wiremock

import arrow.core.some
import com.trendyol.stove.interactions.MockWarning
import com.trendyol.stove.interactions.MockWarningKind
import com.trendyol.stove.interactions.MockWarningListener
import com.trendyol.stove.system.Stove
import com.trendyol.stove.system.stove
import com.trendyol.stove.tracing.TraceContext
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.delay
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpRequest.BodyPublishers
import java.net.http.HttpResponse.BodyHandlers
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Warnings are raised only from provable evidence and never fail tests: unused stubs and
 * unvalidated unmatched requests at test end, cross-test matches at serve time.
 */
class WireMockWarningTest :
  FunSpec({
    val client = HttpClient.newBuilder().build()
    val warnings = CopyOnWriteArrayList<MockWarning>()
    val listener = MockWarningListener { warnings.add(it) }
    lateinit var producingTestId: String
    lateinit var validatedTestId: String

    suspend fun awaitUntil(timeoutMs: Long = 5_000, condition: () -> Boolean) {
      val deadline = System.currentTimeMillis() + timeoutMs
      while (!condition() && System.currentTimeMillis() < deadline) delay(50)
      condition().shouldBeTrue()
    }

    test("a test producing warning-worthy evidence") {
      producingTestId = Stove.reporter().currentTestId()

      stove {
        wiremock {
          addWarningListener(listener)

          // Never called: unused-stub warning at test end.
          mockGet(url = "/warnings/never-called", statusCode = 200, responseBody = mapOf("ok" to true).some())

          // Called with a foreign test id against this test's stub: cross-test match at serve time.
          mockGet(url = "/warnings/crossed", statusCode = 200, responseBody = mapOf("ok" to true).some())
          client
            .send(
              HttpRequest
                .newBuilder(URI("$WIREMOCK_BASE_URL/warnings/crossed"))
                .header(TraceContext.STOVE_TEST_ID_HEADER, "an-entirely-different-test")
                .GET()
                .build(),
              BodyHandlers.ofString()
            ).statusCode() shouldBe 200

          // Provably this test's unmatched request, and validate() is never called.
          client
            .send(
              HttpRequest
                .newBuilder(URI("$WIREMOCK_BASE_URL/warnings/unmatched"))
                .header("Content-Type", "application/json")
                .header(TraceContext.STOVE_TEST_ID_HEADER, producingTestId)
                .POST(BodyPublishers.ofString("{}"))
                .build(),
              BodyHandlers.ofString()
            ).statusCode() shouldBe 404

          awaitUntil { warnings.any { it.kind == MockWarningKind.CROSS_TEST_MATCH } }
          val crossed = warnings.single { it.kind == MockWarningKind.CROSS_TEST_MATCH }
          crossed.testId shouldBe "an-entirely-different-test"
          crossed.message shouldContain producingTestId
          crossed.target shouldBe "/warnings/crossed"
        }
      }
    }

    test("test-end warnings were raised for the previous test") {
      val unused = warnings.filter { it.kind == MockWarningKind.UNUSED_STUB && it.testId == producingTestId }
      unused.any { it.target == "GET /warnings/never-called" }.shouldBeTrue()
      unused.none { it.target == "GET /warnings/crossed" }.shouldBeTrue()

      val unvalidated = warnings.single {
        it.kind == MockWarningKind.UNVALIDATED_UNMATCHED && it.testId == producingTestId
      }
      unvalidated.message shouldContain "validate() would have failed"
      unvalidated.target shouldBe "/warnings/unmatched"
    }

    test("calling validate suppresses the unvalidated unmatched warning") {
      validatedTestId = Stove.reporter().currentTestId()
      stove {
        wiremock {
          client
            .send(
              HttpRequest
                .newBuilder(URI("$WIREMOCK_BASE_URL/warnings/explicitly-validated"))
                .header(TraceContext.STOVE_TEST_ID_HEADER, validatedTestId)
                .GET()
                .build(),
              BodyHandlers.ofString()
            ).statusCode() shouldBe 404
          io.kotest.assertions.throwables.shouldThrow<AssertionError> { validate() }
        }
      }
    }

    test("validated unmatched evidence did not produce a misleading warning") {
      try {
        warnings.none {
          it.kind == MockWarningKind.UNVALIDATED_UNMATCHED && it.testId == validatedTestId
        }.shouldBeTrue()
      } finally {
        stove { wiremock { removeWarningListener(listener) } }
      }
    }
  })
