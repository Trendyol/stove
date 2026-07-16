package com.trendyol.stove.testing.grpcmock

import com.trendyol.stove.grpc.grpc
import com.trendyol.stove.system.Stove
import com.trendyol.stove.system.stove
import com.trendyol.stove.testing.grpcmock.test.*
import io.grpc.CallOptions
import io.grpc.ClientInterceptors
import io.grpc.Metadata
import io.grpc.MethodDescriptor
import io.grpc.protobuf.ProtoUtils
import io.grpc.stub.ClientCalls
import io.grpc.stub.MetadataUtils
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.io.ByteArrayInputStream
import java.io.InputStream

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
          shouldHaveBeenCalled("test.TestService", "Unary", TestRequest.parser()) {
            it.message == "order-123" && it.count == 7
          }
          shouldNotHaveBeenCalled("test.TestService", "Unary", TestRequest.parser()) {
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
            shouldHaveBeenCalled("test.TestService", "Unary", TestRequest.parser()) {
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
            requestMatcher = RequestMatcher.message(TestServiceGrpc.getUnaryMethod()) { it.message == "typed" },
            response = TestResponse.newBuilder().setMessage("descriptor-matched").build()
          )
        }

        grpc {
          channel<TestServiceGrpcKt.TestServiceCoroutineStub> {
            unary(testRequest { message = "typed" }).message shouldBe "descriptor-matched"
          }
        }

        grpcMock {
          shouldHaveBeenCalled(TestServiceGrpc.getUnaryMethod()) { it.message == "typed" }
        }
      }
    }

    test("typed negative verification fails closed when request decoding fails") {
      val rawUnaryMethod = MethodDescriptor
        .newBuilder<ByteArray, TestResponse>()
        .setType(MethodDescriptor.MethodType.UNARY)
        .setFullMethodName(TestServiceGrpc.getUnaryMethod().fullMethodName)
        .setRequestMarshaller(ByteArrayTestMarshaller)
        .setResponseMarshaller(ProtoUtils.marshaller(TestResponse.getDefaultInstance()))
        .build()

      stove {
        grpcMock {
          mockUnary(
            serviceName = "test.TestService",
            methodName = "Unary",
            response = testResponse { message = "raw-ok" }
          )
        }

        grpc {
          rawChannel { channel ->
            val headers = Metadata().apply {
              put(
                Metadata.Key.of("x-stove-test-id", Metadata.ASCII_STRING_MARSHALLER),
                Stove.reporter().currentTestId()
              )
            }
            val taggedChannel = ClientInterceptors.intercept(
              channel,
              MetadataUtils.newAttachHeadersInterceptor(headers)
            )
            ClientCalls
              .blockingUnaryCall(
                taggedChannel,
                rawUnaryMethod,
                CallOptions.DEFAULT,
                byteArrayOf(0)
              ).message shouldBe "raw-ok"
          }
        }

        grpcMock {
          val error = shouldThrow<AssertionError> {
            shouldNotHaveBeenCalled("test.TestService", "Unary", TestRequest.parser())
          }
          error.message shouldContain "could not be decoded with the supplied protobuf parser"
          error.message shouldContain "the predicate was not evaluated"
          error.message shouldContain "InvalidProtocolBufferException"
        }
      }
    }

    test("error stubs are type-agnostic and do not conflict with streaming stubs") {
      stove {
        grpcMock {
          mockError(
            serviceName = "test.TestService",
            methodName = "ServerStream",
            requestMatcher = RequestMatcher.message(TestRequest.parser()) { it.message == "boom" },
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

private object ByteArrayTestMarshaller : MethodDescriptor.Marshaller<ByteArray> {
  override fun stream(value: ByteArray): InputStream = ByteArrayInputStream(value)

  override fun parse(stream: InputStream): ByteArray = stream.readBytes()
}
