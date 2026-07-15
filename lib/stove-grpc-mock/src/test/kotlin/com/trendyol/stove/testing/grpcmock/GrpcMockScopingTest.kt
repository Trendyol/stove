package com.trendyol.stove.testing.grpcmock

import com.trendyol.stove.grpc.grpc
import com.trendyol.stove.scoping.stoveTestId
import com.trendyol.stove.system.stove
import com.trendyol.stove.testing.grpcmock.test.*
import io.grpc.Metadata
import io.grpc.Status
import io.grpc.StatusException
import io.grpc.StatusRuntimeException
import io.grpc.stub.MetadataUtils
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * Test-scoped, fail-open request journaling: a request is excluded from a test's view
 * only when it is provably tagged with a different test id.
 */
class GrpcMockScopingTest :
  FunSpec({
    suspend fun registerBearerOnlyStub() {
      stove {
        grpcMock {
          mockUnary(
            serviceName = "test.TestService",
            methodName = "Unary",
            metadataMatcher = MetadataMatcher.BearerToken("required-token"),
            response = TestResponse.newBuilder().setMessage("authorized").build()
          )
        }
      }
    }

    test("a test's own unmatched request fails its validate") {
      registerBearerOnlyStub()

      stove {
        grpc {
          // Stove's channel propagates the current test id, so this unmatched
          // request is provably ours.
          channel<TestServiceGrpcKt.TestServiceCoroutineStub> {
            val exception = shouldThrow<StatusException> {
              unary(testRequest { message = "no auth" })
            }
            exception.status.code shouldBe Status.Code.UNIMPLEMENTED
          }
        }

        grpcMock {
          val error = shouldThrow<AssertionError> { validate() }
          error.message shouldContain "test.TestService/Unary"
        }
      }
    }

    test("another test's unmatched request does not fail validate") {
      // The previous test left an unmatched request tagged with its own test id.
      stove {
        grpcMock { validate() }
      }
    }

    test("unmatched request provably tagged with a different test id does not fail validate") {
      registerBearerOnlyStub()

      stove {
        grpc {
          rawChannel { ch ->
            val foreign = Metadata()
            foreign.put(
              Metadata.Key.of("x-stove-test-id", Metadata.ASCII_STRING_MARSHALLER),
              "an-entirely-different-test"
            )
            val stub = TestServiceGrpc
              .newBlockingStub(ch)
              .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(foreign))
            val exception = shouldThrow<StatusRuntimeException> {
              stub.unary(testRequest { message = "no auth" })
            }
            exception.status.code shouldBe Status.Code.UNIMPLEMENTED
          }
        }

        grpcMock { validate() }
      }
    }

    test("untagged unmatched request fails validate for every test (fail-open)") {
      registerBearerOnlyStub()

      stove {
        grpc {
          rawChannel { ch ->
            // No Stove interceptor, no test-id metadata: the request is untagged.
            val exception = shouldThrow<StatusRuntimeException> {
              TestServiceGrpc.newBlockingStub(ch).unary(testRequest { message = "no auth" })
            }
            exception.status.code shouldBe Status.Code.UNIMPLEMENTED
          }
        }

        grpcMock {
          val error = shouldThrow<AssertionError> { validate() }
          error.message shouldContain "test.TestService/Unary"
        }
      }
    }

    context("metadata attribution") {
      test("test id is extracted from metadata header or baggage") {
        val header = Metadata()
        header.put(Metadata.Key.of("x-stove-test-id", Metadata.ASCII_STRING_MARSHALLER), "test-1")
        header.toHeaderMap().stoveTestId() shouldBe "test-1"

        val baggage = Metadata()
        baggage.put(Metadata.Key.of("baggage", Metadata.ASCII_STRING_MARSHALLER), "stove.test.id=my%20test")
        baggage.toHeaderMap().stoveTestId() shouldBe "my test"

        Metadata().toHeaderMap().stoveTestId().shouldBeNull()
      }
    }
  })
