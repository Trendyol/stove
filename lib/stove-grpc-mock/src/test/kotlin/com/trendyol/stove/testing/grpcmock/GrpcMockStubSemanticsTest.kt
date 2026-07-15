package com.trendyol.stove.testing.grpcmock

import com.trendyol.stove.grpc.grpc
import com.trendyol.stove.system.stove
import com.trendyol.stove.testing.grpcmock.test.*
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.flow.map

/**
 * Stub registry semantics: last-registered stub wins among matching candidates,
 * and a gRPC method accepts stubs of exactly one method type.
 */
class GrpcMockStubSemanticsTest :
  FunSpec({
    test("last registered matching stub wins, and removal falls back to the earlier stub") {
      stove {
        grpcMock {
          mockUnary(
            serviceName = "test.TestService",
            methodName = "Unary",
            response = TestResponse.newBuilder().setMessage("first").build()
          )
          mockUnary(
            serviceName = "test.TestService",
            methodName = "Unary",
            response = TestResponse.newBuilder().setMessage("second").build()
          )
        }

        grpc {
          channel<TestServiceGrpcKt.TestServiceCoroutineStub> {
            // Both stubs match; the later registration overrides the earlier one.
            unary(testRequest { message = "hi" }).message shouldBe "second"
            // removeStubAfterRequestMatched removed the winner; the earlier stub serves next.
            unary(testRequest { message = "hi" }).message shouldBe "first"
          }
        }
      }
    }

    test("registering a stub of a different method type for the same method fails fast") {
      stove {
        grpcMock {
          mockUnary(
            serviceName = "test.TestService",
            methodName = "TypeConflictProbe",
            response = TestResponse.newBuilder().setMessage("unary").build()
          )

          val error = shouldThrow<IllegalArgumentException> {
            mockServerStream(
              serviceName = "test.TestService",
              methodName = "TypeConflictProbe",
              responses = listOf(Item.newBuilder().setId("1").build())
            )
          }
          error.message shouldContain "exactly one type"
        }
      }
    }

    test("bidi stubs reject request matchers instead of silently ignoring them") {
      stove {
        grpcMock {
          val error = shouldThrow<IllegalArgumentException> {
            mockBidiStream(
              serviceName = "test.TestService",
              methodName = "BidiStream",
              requestMatcher = RequestMatcher.ExactBytes(byteArrayOf(1))
            ) { requests -> requests.map { TestResponse.getDefaultInstance() } }
          }
          error.message shouldContain "not supported for bidi streams"
        }
      }
    }
  })
