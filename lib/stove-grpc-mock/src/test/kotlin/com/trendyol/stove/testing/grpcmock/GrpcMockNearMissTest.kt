package com.trendyol.stove.testing.grpcmock

import com.trendyol.stove.grpc.grpc
import com.trendyol.stove.system.stove
import com.trendyol.stove.testing.grpcmock.test.*
import io.grpc.Status
import io.grpc.StatusException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

/**
 * validate() failures explain which matcher rejected each candidate stub,
 * captured at request time so diagnostics survive stub removal.
 */
class GrpcMockNearMissTest :
  FunSpec({
    test("metadata rejection is named in the validate failure") {
      stove {
        grpcMock {
          mockUnary(
            serviceName = "test.TestService",
            methodName = "Unary",
            metadataMatcher = MetadataMatcher.BearerToken("expected-token"),
            response = TestResponse.newBuilder().setMessage("authorized").build()
          )
        }

        grpc {
          channel<TestServiceGrpcKt.TestServiceCoroutineStub> {
            val exception = shouldThrow<StatusException> { unary(testRequest { message = "no auth" }) }
            exception.status.code shouldBe Status.Code.UNIMPLEMENTED
          }
        }

        grpcMock {
          val error = shouldThrow<AssertionError> { validate() }
          error.message shouldContain "metadata matcher rejected"
          error.message shouldContain "Bearer token"
        }
      }
    }

    test("calls to methods with no stubs at all are journaled, not silently dropped") {
      // Manually built descriptor: no generated stub exists for this method anywhere.
      val unknownMethod = io.grpc.MethodDescriptor
        .newBuilder<TestRequest, TestResponse>()
        .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
        .setFullMethodName("test.TestService/DoesNotExist")
        .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(TestRequest.getDefaultInstance()))
        .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(TestResponse.getDefaultInstance()))
        .build()

      stove {
        grpc {
          rawChannel { ch ->
            // Tag the call with this test's id so the journaled evidence stays scoped to it.
            val testIdMetadata = io.grpc.Metadata().apply {
              put(
                io.grpc.Metadata.Key.of("x-stove-test-id", io.grpc.Metadata.ASCII_STRING_MARSHALLER),
                com.trendyol.stove.system.Stove
                  .reporter()
                  .currentTestId()
              )
            }
            val intercepted = io.grpc.ClientInterceptors.intercept(
              ch,
              io.grpc.stub.MetadataUtils
                .newAttachHeadersInterceptor(testIdMetadata)
            )
            val exception = shouldThrow<io.grpc.StatusRuntimeException> {
              io.grpc.stub.ClientCalls.blockingUnaryCall(
                intercepted,
                unknownMethod,
                io.grpc.CallOptions.DEFAULT,
                testRequest { message = "typo'd method" }
              )
            }
            exception.status.code shouldBe Status.Code.UNIMPLEMENTED
          }
        }

        grpcMock {
          val error = shouldThrow<AssertionError> { validate() }
          error.message shouldContain "test.TestService/DoesNotExist"
          error.message shouldContain "no stubs registered for this method"
        }
      }
    }

    test("exact-message rejection redacts parsed payloads by default") {
      stove {
        grpcMock {
          mockUnary(
            serviceName = "test.TestService",
            methodName = "Unary",
            requestMatcher = RequestMatcher.ExactMessage(testRequest { message = "expected-payload" }),
            response = TestResponse.newBuilder().setMessage("matched").build()
          )
        }

        grpc {
          channel<TestServiceGrpcKt.TestServiceCoroutineStub> {
            val exception = shouldThrow<StatusException> { unary(testRequest { message = "actual-payload" }) }
            exception.status.code shouldBe Status.Code.UNIMPLEMENTED
          }
        }

        grpcMock {
          val error = shouldThrow<AssertionError> { validate() }
          error.message shouldContain "request matcher rejected"
          error.message shouldContain "payload redacted"
          error.message shouldNotContain "expected-payload"
          error.message shouldNotContain "actual-payload"
        }
      }
    }

    test("exact-message diagnostics accept an explicit bounded redactor") {
      val evaluation = RequestMatcher
        .ExactMessage(
          message = testRequest { message = "expected-payload" },
          diagnosticPayloadRedactor = { message -> (message as TestRequest).message }
        ).evaluate(testRequest { message = "actual-payload" }.toByteArray())

      evaluation.matched shouldBe false
      evaluation.rejection shouldContain "expected-payload"
      evaluation.rejection shouldContain "actual-payload"
    }

    test("typed-message rejection distinguishes malformed protobuf bytes") {
      val evaluation = RequestMatcher
        .message(TestRequest.parser()) { true }
        .evaluate(byteArrayOf(0))

      evaluation.matched shouldBe false
      evaluation.rejection shouldContain "could not decode request with the supplied protobuf parser"
      evaluation.rejection shouldContain "InvalidProtocolBufferException"
    }
  })
