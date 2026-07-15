package com.trendyol.stove.testing.grpcmock

import com.trendyol.stove.grpc.grpc
import com.trendyol.stove.system.stove
import com.trendyol.stove.testing.grpcmock.test.*
import io.grpc.Metadata
import io.grpc.Status
import io.grpc.StatusException
import io.grpc.health.v1.HealthCheckRequest
import io.grpc.health.v1.HealthCheckResponse
import io.grpc.health.v1.HealthGrpc
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.milliseconds

/**
 * Fidelity features: per-stub latency for deadline testing, streams that fail
 * mid-flight, structured error trailers, and the built-in health service.
 */
class GrpcMockFidelityTest :
  FunSpec({
    test("per-stub delay makes client deadlines expire") {
      stove {
        grpcMock {
          // Matcher pinned to this test's payload: a client deadline can cancel the call
          // before the server consumes the stub, and an Any matcher would then leak into
          // other tests' calls on the same method.
          mockUnary(
            serviceName = "test.TestService",
            methodName = "Unary",
            requestMatcher = RequestMatcher.message<TestRequest> { it.message == "deadline-probe" },
            response = TestResponse.newBuilder().setMessage("late").build(),
            delay = 500.milliseconds
          )
        }

        grpc {
          rawChannel { ch ->
            val stub = TestServiceGrpc
              .newBlockingStub(ch)
              .withDeadlineAfter(100, TimeUnit.MILLISECONDS)
            val exception = shouldThrow<io.grpc.StatusRuntimeException> {
              stub.unary(testRequest { message = "deadline-probe" })
            }
            exception.status.code shouldBe Status.Code.DEADLINE_EXCEEDED
          }
        }
      }
    }

    test("server stream can emit items and then fail mid-stream") {
      stove {
        grpcMock {
          mockServerStream(
            serviceName = "test.TestService",
            methodName = "ServerStream",
            responses = listOf(
              Item.newBuilder().setId("1").build(),
              Item.newBuilder().setId("2").build()
            ),
            thenFailWith = Status.UNAVAILABLE.withDescription("broker gone mid-stream")
          )
        }

        grpc {
          channel<TestServiceGrpcKt.TestServiceCoroutineStub> {
            val received = mutableListOf<Item>()
            val exception = shouldThrow<StatusException> {
              serverStream(testRequest { message = "stream" }).collect { received.add(it) }
            }
            received shouldHaveSize 2
            received.map { it.id } shouldBe listOf("1", "2")
            exception.status.code shouldBe Status.Code.UNAVAILABLE
            exception.status.description shouldContain "mid-stream"
          }
        }
      }
    }

    test("error stubs carry trailers like real structured gRPC errors") {
      val errorCodeKey = Metadata.Key.of("x-error-code", Metadata.ASCII_STRING_MARSHALLER)
      stove {
        grpcMock {
          mockError(
            serviceName = "test.TestService",
            methodName = "Unary",
            status = Status.Code.FAILED_PRECONDITION,
            message = "insufficient funds",
            trailers = Metadata().apply { put(errorCodeKey, "INSUFFICIENT_FUNDS") }
          )
        }

        grpc {
          channel<TestServiceGrpcKt.TestServiceCoroutineStub> {
            val exception = shouldThrow<StatusException> { unary(testRequest { message = "charge" }) }
            exception.status.code shouldBe Status.Code.FAILED_PRECONDITION
            exception.trailers?.get(errorCodeKey) shouldBe "INSUFFICIENT_FUNDS"
          }
        }
      }
    }

    test("built-in health service answers without any stubbing") {
      stove {
        grpc {
          rawChannel { ch ->
            val health = HealthGrpc.newBlockingStub(ch)
            val response = health.check(HealthCheckRequest.getDefaultInstance())
            response.status shouldBe HealthCheckResponse.ServingStatus.SERVING
          }
        }
      }
    }
  })
