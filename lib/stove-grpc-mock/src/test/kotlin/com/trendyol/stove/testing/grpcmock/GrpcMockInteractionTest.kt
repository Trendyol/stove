package com.trendyol.stove.testing.grpcmock

import com.trendyol.stove.grpc.grpc
import com.trendyol.stove.interactions.InteractionAttribution
import com.trendyol.stove.interactions.MockInteraction
import com.trendyol.stove.interactions.MockInteractionListener
import com.trendyol.stove.system.Stove
import com.trendyol.stove.system.stove
import com.trendyol.stove.testing.grpcmock.test.*
import io.grpc.Status
import io.grpc.StatusException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.delay
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The call-observing interceptor emits one interaction per call with final status,
 * latency, payload sizes, and proven-only attribution.
 */
class GrpcMockInteractionTest :
  FunSpec({
    suspend fun awaitUntil(timeoutMs: Long = 5_000, condition: () -> Boolean) {
      val deadline = System.currentTimeMillis() + timeoutMs
      while (!condition() && System.currentTimeMillis() < deadline) delay(50)
      condition().shouldBeTrue()
    }

    test("matched and unmatched calls are emitted with final status and attribution") {
      val interactions = CopyOnWriteArrayList<MockInteraction>()
      val listener = MockInteractionListener { interactions.add(it) }

      stove {
        grpcMock {
          addInteractionListener(listener)
          mockUnary(
            serviceName = "test.TestService",
            methodName = "Unary",
            requestMatcher = RequestMatcher.message<TestRequest> { it.message == "observe-me" },
            response = TestResponse.newBuilder().setMessage("observed").build()
          )
        }

        try {
          grpc {
            channel<TestServiceGrpcKt.TestServiceCoroutineStub> {
              // Matched: the Stove channel carries the test id header.
              unary(testRequest { message = "observe-me" }).message shouldBe "observed"
              // Unmatched: payload rejected by the matcher.
              val exception = shouldThrow<StatusException> { unary(testRequest { message = "not-me" }) }
              exception.status.code shouldBe Status.Code.UNIMPLEMENTED
            }
          }

          awaitUntil { interactions.count { it.target == "test.TestService/Unary" } >= 2 }

          val matched = interactions.single { it.target == "test.TestService/Unary" && it.matched }
          matched.protocol shouldBe MockInteraction.Protocol.GRPC
          matched.attribution shouldBe InteractionAttribution.PROVEN_HEADER
          matched.testId shouldBe Stove.reporter().currentTestId()
          matched.status shouldBe "OK"
          matched.stubId.shouldNotBeNull()
          matched.latencyMs.shouldNotBeNull()

          val unmatched = interactions.single { it.target == "test.TestService/Unary" && !it.matched }
          unmatched.attribution shouldBe InteractionAttribution.PROVEN_HEADER
          unmatched.status shouldBe "UNIMPLEMENTED"
          unmatched.nearMisses.shouldNotBeEmpty()
        } finally {
          grpcMock { removeInteractionListener(listener) }
        }
      }
    }
  })
