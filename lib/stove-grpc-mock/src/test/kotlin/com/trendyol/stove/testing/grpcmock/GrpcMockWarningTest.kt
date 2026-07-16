package com.trendyol.stove.testing.grpcmock

import com.trendyol.stove.grpc.grpc
import com.trendyol.stove.interactions.MockWarning
import com.trendyol.stove.interactions.MockWarningKind
import com.trendyol.stove.interactions.MockWarningListener
import com.trendyol.stove.system.Stove
import com.trendyol.stove.system.stove
import com.trendyol.stove.testing.grpcmock.test.*
import io.grpc.ClientInterceptors
import io.grpc.Metadata
import io.grpc.stub.MetadataUtils
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.delay
import java.util.concurrent.CopyOnWriteArrayList

/**
 * gRPC warnings mirror the WireMock ones: unused stubs and unvalidated unmatched calls at
 * test end, cross-test matches at serve time — all from provable evidence only.
 */
class GrpcMockWarningTest :
  FunSpec({
    val warnings = CopyOnWriteArrayList<MockWarning>()
    val listener = MockWarningListener { warnings.add(it) }
    lateinit var producingTestId: String

    suspend fun awaitUntil(timeoutMs: Long = 5_000, condition: () -> Boolean) {
      val deadline = System.currentTimeMillis() + timeoutMs
      while (!condition() && System.currentTimeMillis() < deadline) delay(50)
      condition().shouldBeTrue()
    }

    test("a test producing warning-worthy evidence") {
      producingTestId = Stove.reporter().currentTestId()

      stove {
        grpcMock {
          addWarningListener(listener)
          // Never called: unused-stub warning at test end.
          mockUnary(
            serviceName = "test.TestService",
            methodName = "WarningsNeverCalled",
            response = TestResponse.newBuilder().setMessage("lonely").build()
          )
          // Will be called with a foreign test id: cross-test match at serve time.
          mockUnary(
            serviceName = "test.TestService",
            methodName = "Unary",
            requestMatcher = RequestMatcher.message<TestRequest> { it.message == "crossed" },
            response = TestResponse.newBuilder().setMessage("served").build()
          )
        }

        grpc {
          rawChannel { ch ->
            val foreign = Metadata().apply {
              put(Metadata.Key.of("x-stove-test-id", Metadata.ASCII_STRING_MARSHALLER), "an-entirely-different-test")
            }
            val stub = TestServiceGrpc
              .newBlockingStub(ClientInterceptors.intercept(ch, MetadataUtils.newAttachHeadersInterceptor(foreign)))
            stub.unary(testRequest { message = "crossed" }).message shouldBe "served"
          }
        }

        awaitUntil { warnings.any { it.kind == MockWarningKind.CROSS_TEST_MATCH } }
        val crossed = warnings.single { it.kind == MockWarningKind.CROSS_TEST_MATCH }
        crossed.testId shouldBe "an-entirely-different-test"
        crossed.message shouldContain producingTestId
        crossed.target shouldBe "test.TestService/Unary"
      }
    }

    test("test-end warnings were raised for the previous test") {
      try {
        val unused = warnings.filter { it.kind == MockWarningKind.UNUSED_STUB && it.testId == producingTestId }
        unused.any { it.target == "test.TestService/WarningsNeverCalled" }.shouldBeTrue()
      } finally {
        stove { grpcMock { removeWarningListener(listener) } }
      }
    }
  })
