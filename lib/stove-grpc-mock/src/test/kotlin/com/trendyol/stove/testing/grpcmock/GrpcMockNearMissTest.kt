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

    test("exact-message rejection shows expected versus received payloads") {
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
          error.message shouldContain "expected-payload"
          error.message shouldContain "actual-payload"
        }
      }
    }
  })
