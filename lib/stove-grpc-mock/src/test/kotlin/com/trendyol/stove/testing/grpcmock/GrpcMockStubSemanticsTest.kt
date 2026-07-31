package com.trendyol.stove.testing.grpcmock

import com.trendyol.stove.grpc.grpc
import com.trendyol.stove.system.stove
import com.trendyol.stove.testing.grpcmock.test.*
import io.grpc.Status
import io.grpc.StatusRuntimeException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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

    test("a one-shot stub is consumed by only one concurrent request") {
      stove {
        grpcMock {
          mockUnary(
            serviceName = "test.TestService",
            methodName = "Unary",
            requestMatcher = RequestMatcher.message(TestRequest.parser()) {
              it.message == "concurrent-one-shot"
            },
            response = TestResponse.newBuilder().setMessage("served-once").build()
          )
        }

        grpc {
          rawChannel { channel ->
            val stub = TestServiceGrpc.newBlockingStub(channel)
            val outcomes = coroutineScope {
              List(2) {
                async(Dispatchers.IO) {
                  runCatching { stub.unary(testRequest { message = "concurrent-one-shot" }) }
                }
              }.awaitAll()
            }

            outcomes.count { it.getOrNull()?.message == "served-once" } shouldBe 1
            val failure = outcomes.single { it.isFailure }.exceptionOrNull()
            (failure as StatusRuntimeException).status.code shouldBe Status.Code.UNIMPLEMENTED
          }
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
