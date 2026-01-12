package com.trendyol.stove.testing.grpcmock

import com.trendyol.stove.grpc.grpc
import com.trendyol.stove.system.stove
import com.trendyol.stove.testing.grpcmock.test.*
import io.grpc.*
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.flow.*

/**
 * Tests for the native gRPC mock system.
 */
class GrpcMockSystemTest :
  FunSpec({
    context("Unary RPC") {
      test("should mock unary call and receive response") {
        stove {
          grpcMock {
            mockUnary(
              serviceName = "test.TestService",
              methodName = "Unary",
              response = TestResponse
                .newBuilder()
                .setMessage("Hello from mock!")
                .setCount(42)
                .build()
            )
          }

          grpc {
            channel<TestServiceGrpcKt.TestServiceCoroutineStub> {
              val request = testRequest {
                message = "Hello"
                count = 1
              }
              val response = unary(request)
              response.message shouldBe "Hello from mock!"
              response.count shouldBe 42
            }
          }
        }
      }

      test("should mock unary call with request matching") {
        stove {
          grpcMock {
            mockUnary(
              serviceName = "test.TestService",
              methodName = "Unary",
              requestMatcher = RequestMatcher.ExactMessage(
                TestRequest
                  .newBuilder()
                  .setMessage("specific")
                  .setCount(100)
                  .build()
              ),
              response = TestResponse
                .newBuilder()
                .setMessage("Matched specific request!")
                .build()
            )
          }

          grpc {
            channel<TestServiceGrpcKt.TestServiceCoroutineStub> {
              val request = testRequest {
                message = "specific"
                count = 100
              }
              val response = unary(request)
              response.message shouldBe "Matched specific request!"
            }
          }
        }
      }

      test("should handle multiple sequential unary calls") {
        stove {
          grpcMock {
            mockUnary(
              serviceName = "test.TestService",
              methodName = "Unary",
              requestMatcher = RequestMatcher.ExactMessage(
                TestRequest.newBuilder().setMessage("first").build()
              ),
              response = TestResponse.newBuilder().setMessage("First response").build()
            )
            mockUnary(
              serviceName = "test.TestService",
              methodName = "Unary",
              requestMatcher = RequestMatcher.ExactMessage(
                TestRequest.newBuilder().setMessage("second").build()
              ),
              response = TestResponse.newBuilder().setMessage("Second response").build()
            )
          }

          grpc {
            channel<TestServiceGrpcKt.TestServiceCoroutineStub> {
              val response1 = unary(testRequest { message = "first" })
              response1.message shouldBe "First response"

              val response2 = unary(testRequest { message = "second" })
              response2.message shouldBe "Second response"
            }
          }
        }
      }
    }

    context("Error responses") {
      test("should mock NOT_FOUND error") {
        stove {
          grpcMock {
            mockError(
              serviceName = "test.TestService",
              methodName = "Unary",
              status = Status.Code.NOT_FOUND,
              message = "Resource not found"
            )
          }

          grpc {
            channel<TestServiceGrpcKt.TestServiceCoroutineStub> {
              val exception = shouldThrow<StatusException> {
                unary(testRequest { message = "test" })
              }
              exception.status.code shouldBe Status.Code.NOT_FOUND
              exception.status.description shouldContain "Resource not found"
            }
          }
        }
      }

      test("should mock UNAUTHENTICATED error") {
        stove {
          grpcMock {
            mockError(
              serviceName = "test.TestService",
              methodName = "Unary",
              status = Status.Code.UNAUTHENTICATED,
              message = "Invalid credentials"
            )
          }

          grpc {
            channel<TestServiceGrpcKt.TestServiceCoroutineStub> {
              val exception = shouldThrow<StatusException> {
                unary(testRequest { message = "test" })
              }
              exception.status.code shouldBe Status.Code.UNAUTHENTICATED
            }
          }
        }
      }

      test("should mock INVALID_ARGUMENT error") {
        stove {
          grpcMock {
            mockError(
              serviceName = "test.TestService",
              methodName = "Unary",
              status = Status.Code.INVALID_ARGUMENT,
              message = "Invalid input"
            )
          }

          grpc {
            channel<TestServiceGrpcKt.TestServiceCoroutineStub> {
              val exception = shouldThrow<StatusException> {
                unary(testRequest { message = "" })
              }
              exception.status.code shouldBe Status.Code.INVALID_ARGUMENT
            }
          }
        }
      }
    }

    context("Server streaming RPC") {
      test("should mock server streaming call with multiple responses") {
        stove {
          grpcMock {
            mockServerStream(
              serviceName = "test.TestService",
              methodName = "ServerStream",
              responses = listOf(
                Item
                  .newBuilder()
                  .setId("1")
                  .setName("Item 1")
                  .setValue(100)
                  .build(),
                Item
                  .newBuilder()
                  .setId("2")
                  .setName("Item 2")
                  .setValue(200)
                  .build(),
                Item
                  .newBuilder()
                  .setId("3")
                  .setName("Item 3")
                  .setValue(300)
                  .build()
              )
            )
          }

          grpc {
            channel<TestServiceGrpcKt.TestServiceCoroutineStub> {
              val request = testRequest {
                message = "stream"
                count = 3
              }
              val responses = serverStream(request).toList()

              responses shouldHaveSize 3
              responses[0].id shouldBe "1"
              responses[0].name shouldBe "Item 1"
              responses[1].id shouldBe "2"
              responses[2].id shouldBe "3"
            }
          }
        }
      }
    }

    context("Client streaming RPC") {
      test("should mock client streaming call") {
        stove {
          grpcMock {
            mockClientStream(
              serviceName = "test.TestService",
              methodName = "ClientStream",
              response = TestResponse
                .newBuilder()
                .setMessage("Received all items")
                .setCount(5)
                .build()
            )
          }

          grpc {
            channel<TestServiceGrpcKt.TestServiceCoroutineStub> {
              val requests = kotlinx.coroutines.flow.flowOf(
                testRequest { message = "item1" },
                testRequest { message = "item2" },
                testRequest { message = "item3" }
              )
              val response = clientStream(requests)

              response.message shouldBe "Received all items"
              response.count shouldBe 5
            }
          }
        }
      }
    }

    context("Bidirectional streaming RPC") {
      test("should mock bidi streaming call") {
        stove {
          grpcMock {
            mockBidiStream(
              serviceName = "test.TestService",
              methodName = "BidiStream"
            ) { requestFlow ->
              requestFlow.map { _ ->
                // Echo back with modified message
                TestResponse
                  .newBuilder()
                  .setMessage("Echo response")
                  .setCount(1)
                  .build()
              }
            }
          }

          grpc {
            channel<TestServiceGrpcKt.TestServiceCoroutineStub> {
              val requests = kotlinx.coroutines.flow.flowOf(
                testRequest { message = "hello1" },
                testRequest { message = "hello2" }
              )
              val responses = bidiStream(requests).toList()

              responses shouldHaveSize 2
              responses.forEach { it.message shouldBe "Echo response" }
            }
          }
        }
      }
    }

    context("Authenticated calls") {
      test("should mock authenticated unary call with bearer token") {
        stove {
          grpcMock {
            mockUnary(
              serviceName = "test.TestService",
              methodName = "Unary",
              metadataMatcher = MetadataMatcher.BearerToken("valid-token-123"),
              response = TestResponse
                .newBuilder()
                .setMessage("Authenticated response!")
                .build()
            )
          }

          grpc {
            channel<TestServiceGrpcKt.TestServiceCoroutineStub>(
              metadata = mapOf("authorization" to "Bearer valid-token-123")
            ) {
              val response = unary(testRequest { message = "secure request" })
              response.message shouldBe "Authenticated response!"
            }
          }
        }
      }

      test("should reject unauthenticated request when token required") {
        stove {
          grpcMock {
            // Only match if token is provided
            mockUnary(
              serviceName = "test.TestService",
              methodName = "Unary",
              metadataMatcher = MetadataMatcher.BearerToken("required-token"),
              response = TestResponse.newBuilder().setMessage("success").build()
            )
          }

          grpc {
            // Call without token should fail (no matching stub)
            channel<TestServiceGrpcKt.TestServiceCoroutineStub> {
              val exception = shouldThrow<StatusException> {
                unary(testRequest { message = "no auth" })
              }
              exception.status.code shouldBe Status.Code.UNIMPLEMENTED
            }
          }
        }
      }

      test("should reject request with wrong token") {
        stove {
          grpcMock {
            mockUnary(
              serviceName = "test.TestService",
              methodName = "Unary",
              metadataMatcher = MetadataMatcher.BearerToken("correct-token"),
              response = TestResponse.newBuilder().setMessage("success").build()
            )
          }

          grpc {
            // Call with wrong token should fail
            channel<TestServiceGrpcKt.TestServiceCoroutineStub>(
              metadata = mapOf("authorization" to "Bearer wrong-token")
            ) {
              val exception = shouldThrow<StatusException> {
                unary(testRequest { message = "wrong auth" })
              }
              exception.status.code shouldBe Status.Code.UNIMPLEMENTED
            }
          }
        }
      }

      test("should match custom header") {
        stove {
          grpcMock {
            mockUnary(
              serviceName = "test.TestService",
              methodName = "Unary",
              metadataMatcher = MetadataMatcher.HasHeader("x-api-key", "secret-key-abc"),
              response = TestResponse
                .newBuilder()
                .setMessage("API key verified!")
                .build()
            )
          }

          grpc {
            channel<TestServiceGrpcKt.TestServiceCoroutineStub>(
              metadata = mapOf("x-api-key" to "secret-key-abc")
            ) {
              val response = unary(testRequest { message = "api request" })
              response.message shouldBe "API key verified!"
            }
          }
        }
      }

      test("should support RequiresAuth matcher for any auth header") {
        stove {
          grpcMock {
            mockUnary(
              serviceName = "test.TestService",
              methodName = "Unary",
              metadataMatcher = MetadataMatcher.RequiresAuth,
              response = TestResponse
                .newBuilder()
                .setMessage("Some auth provided")
                .build()
            )
          }

          grpc {
            // Any authorization header should work
            channel<TestServiceGrpcKt.TestServiceCoroutineStub>(
              metadata = mapOf("authorization" to "Basic dXNlcjpwYXNz")
            ) {
              val response = unary(testRequest { message = "basic auth" })
              response.message shouldBe "Some auth provided"
            }
          }
        }
      }

      test("should support combined matchers") {
        stove {
          grpcMock {
            mockUnary(
              serviceName = "test.TestService",
              methodName = "Unary",
              metadataMatcher = MetadataMatcher.All(
                MetadataMatcher.BearerToken("valid-token"),
                MetadataMatcher.HasHeader("x-tenant-id", "tenant-123")
              ),
              response = TestResponse
                .newBuilder()
                .setMessage("Multi-header match!")
                .build()
            )
          }

          grpc {
            channel<TestServiceGrpcKt.TestServiceCoroutineStub>(
              metadata = mapOf(
                "authorization" to "Bearer valid-token",
                "x-tenant-id" to "tenant-123"
              )
            ) {
              val response = unary(testRequest { message = "multi auth" })
              response.message shouldBe "Multi-header match!"
            }
          }
        }
      }

      test("should mock authenticated server streaming") {
        stove {
          grpcMock {
            mockServerStream(
              serviceName = "test.TestService",
              methodName = "ServerStream",
              metadataMatcher = MetadataMatcher.BearerToken("stream-token"),
              responses = listOf(
                Item
                  .newBuilder()
                  .setId("1")
                  .setName("Secure Item")
                  .build()
              )
            )
          }

          grpc {
            channel<TestServiceGrpcKt.TestServiceCoroutineStub>(
              metadata = mapOf("authorization" to "Bearer stream-token")
            ) {
              val responses = serverStream(testRequest { message = "stream" }).toList()
              responses shouldHaveSize 1
              responses[0].name shouldBe "Secure Item"
            }
          }
        }
      }
    }

    context("System state") {
      test("snapshot should return system state") {
        stove {
          grpcMock {
            mockUnary(
              serviceName = "test.TestService",
              methodName = "Unary",
              response = TestResponse.newBuilder().setMessage("test").build()
            )

            val snapshot = snapshot()
            snapshot.system shouldBe "gRPC Mock"
            snapshot.summary shouldContain "Registered stubs:"
          }
        }
      }
    }
  })
