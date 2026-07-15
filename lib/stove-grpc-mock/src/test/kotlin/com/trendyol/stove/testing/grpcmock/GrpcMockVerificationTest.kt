package com.trendyol.stove.testing.grpcmock

import com.trendyol.stove.grpc.grpc
import com.trendyol.stove.system.stove
import com.trendyol.stove.testing.grpcmock.test.*
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * Typed, point-in-time verification over the test-scoped journal, and
 * descriptor-typed stubbing/matching.
 */
class GrpcMockVerificationTest :
  FunSpec({
    test("typed shouldHaveBeenCalled matches on parsed request payloads") {
      stove {
        grpcMock {
          mockUnary(
            serviceName = "test.TestService",
            methodName = "Unary",
            response = TestResponse.newBuilder().setMessage("ok").build()
          )
        }

        grpc {
          channel<TestServiceGrpcKt.TestServiceCoroutineStub> {
            unary(
              testRequest {
                message = "order-123"
                count = 7
              }
            )
          }
        }

        grpcMock {
          shouldHaveBeenCalled<TestRequest>("test.TestService", "Unary") {
            it.message == "order-123" && it.count == 7
          }
          shouldNotHaveBeenCalled<TestRequest>("test.TestService", "Unary") {
            it.message == "some-other-order"
          }
        }
      }
    }

    test("typed verification failure reports counts and received payloads") {
      stove {
        grpcMock {
          mockUnary(
            serviceName = "test.TestService",
            methodName = "Unary",
            response = TestResponse.newBuilder().setMessage("ok").build()
          )
        }

        grpc {
          channel<TestServiceGrpcKt.TestServiceCoroutineStub> {
            unary(testRequest { message = "actual-payload" })
          }
        }

        grpcMock {
          val error = shouldThrow<AssertionError> {
            shouldHaveBeenCalled<TestRequest>("test.TestService", "Unary") {
              it.message == "expected-payload"
            }
          }
          error.message shouldContain "Expected exactly 1 request(s)"
          // The total may include untagged fail-open evidence from other specs,
          // so only the matching count is asserted precisely.
          error.message shouldContain "found 0 of"
          error.message shouldContain "actual-payload"
        }
      }
    }

    test("descriptor-typed stubbing and verification work without name strings") {
      stove {
        grpcMock {
          mockUnary(
            method = TestServiceGrpc.getUnaryMethod(),
            requestMatcher = RequestMatcher.message<TestRequest> { it.message == "typed" },
            response = TestResponse.newBuilder().setMessage("descriptor-matched").build()
          )
        }

        grpc {
          channel<TestServiceGrpcKt.TestServiceCoroutineStub> {
            unary(testRequest { message = "typed" }).message shouldBe "descriptor-matched"
          }
        }

        grpcMock {
          shouldHaveBeenCalled<TestRequest>(TestServiceGrpc.getUnaryMethod()) { it.message == "typed" }
        }
      }
    }

    test("error stubs are type-agnostic and do not conflict with streaming stubs") {
      stove {
        grpcMock {
          mockError(
            serviceName = "test.TestService",
            methodName = "ServerStream",
            requestMatcher = RequestMatcher.message<TestRequest> { it.message == "boom" },
            status = io.grpc.Status.Code.UNAVAILABLE
          )
          // Same method, streaming type: must not fail the method-type conflict check.
          mockServerStream(
            serviceName = "test.TestService",
            methodName = "ServerStream",
            responses = listOf(Item.newBuilder().setId("1").build())
          )
        }
      }
    }
  })
