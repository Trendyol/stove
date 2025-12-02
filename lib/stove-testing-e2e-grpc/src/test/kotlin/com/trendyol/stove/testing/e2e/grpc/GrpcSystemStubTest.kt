package com.trendyol.stove.testing.e2e.grpc

import com.trendyol.stove.testing.e2e.grpc.test.TestRequest
import com.trendyol.stove.testing.e2e.grpc.test.TestServiceWireGrpc
import com.trendyol.stove.testing.e2e.system.TestSystem
import io.grpc.CallOptions
import io.grpc.Channel
import io.grpc.ClientCall
import io.grpc.ClientInterceptor
import io.grpc.ClientInterceptors
import io.grpc.ForwardingClientCall
import io.grpc.Metadata
import io.grpc.MethodDescriptor
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Tests for typed channel DSL functionality in GrpcSystem.
 *
 * These tests verify the `channel<T>` DSL method which creates stubs
 * automatically from the managed channel, hiding the boilerplate.
 */
class GrpcSystemStubTest :
  FunSpec({

    test("channel<T> should execute unary call successfully") {
      TestSystem.validate {
        grpc {
          channel<TestServiceWireGrpc.TestServiceStub> {
            val response = Unary(TestRequest(message = "Hello Stub", count = 42))
            response.message shouldBe "Echo: Hello Stub"
            response.count shouldBe 42
            response.success shouldBe true
          }
        }
      }
    }

    test("channel<T> should handle server streaming") {
      TestSystem.validate {
        grpc {
          channel<TestServiceWireGrpc.TestServiceStub> {
            val responses = ServerStream(TestRequest(message = "Stream", count = 3)).toList()

            responses.size shouldBe 3
            responses[0].message shouldBe "Stream - Item 0"
            responses[1].message shouldBe "Stream - Item 1"
            responses[2].message shouldBe "Stream - Item 2"
          }
        }
      }
    }

    test("channel<T> should handle client streaming") {
      TestSystem.validate {
        grpc {
          channel<TestServiceWireGrpc.TestServiceStub> {
            val requestFlow = flow {
              emit(TestRequest(message = "First", count = 1))
              emit(TestRequest(message = "Second", count = 2))
              emit(TestRequest(message = "Third", count = 3))
            }

            val response = ClientStream(requestFlow)
            response.message shouldBe "Received: First, Second, Third"
            response.count shouldBe 6
            response.success shouldBe true
          }
        }
      }
    }

    test("channel<T> should handle bidirectional streaming") {
      TestSystem.validate {
        grpc {
          channel<TestServiceWireGrpc.TestServiceStub> {
            val requestFlow = flow {
              emit(TestRequest(message = "A", count = 1))
              emit(TestRequest(message = "B", count = 2))
            }

            val responses = BidiStream(requestFlow).toList()
            responses.size shouldBe 2
            responses[0].message shouldBe "Echo: A"
            responses[1].message shouldBe "Echo: B"
          }
        }
      }
    }

    test("channel<T> should support multiple sequential calls") {
      TestSystem.validate {
        grpc {
          channel<TestServiceWireGrpc.TestServiceStub> {
            val response1 = Unary(TestRequest(message = "Call 1", count = 1))
            response1.message shouldBe "Echo: Call 1"

            val response2 = Unary(TestRequest(message = "Call 2", count = 2))
            response2.message shouldBe "Echo: Call 2"

            val response3 = Unary(TestRequest(message = "Call 3", count = 3))
            response3.message shouldBe "Echo: Call 3"
          }
        }
      }
    }

    test("channel<T> with per-call metadata should work") {
      TestSystem.validate {
        grpc {
          channel<TestServiceWireGrpc.TestServiceStub>(
            metadata = mapOf("authorization" to "Bearer custom-token")
          ) {
            val response = AuthenticatedCall(TestRequest(message = "Authenticated", count = 1))
            response.message shouldBe "Authenticated: Authenticated"
            response.success shouldBe true
          }
        }
      }
    }

    test("rawChannel should provide direct access to ManagedChannel") {
      TestSystem.validate {
        grpc {
          rawChannel { ch ->
            ch shouldNotBe null
          }
        }
      }
    }

    test("rawChannel with custom interceptor should intercept calls") {
      val interceptedMethods = CopyOnWriteArrayList<String>()

      TestSystem.validate {
        grpc {
          rawChannel { ch ->
            // Create a logging interceptor
            val loggingInterceptor = object : ClientInterceptor {
              override fun <ReqT, RespT> interceptCall(
                method: MethodDescriptor<ReqT, RespT>,
                callOptions: CallOptions,
                next: Channel
              ): ClientCall<ReqT, RespT> {
                interceptedMethods.add(method.fullMethodName)
                return next.newCall(method, callOptions)
              }
            }

            val interceptedChannel = ClientInterceptors.intercept(ch, loggingInterceptor)
            val stub = TestServiceWireGrpc.TestServiceStub(interceptedChannel)

            stub.Unary(TestRequest(message = "Intercepted", count = 1))
          }
        }
      }

      interceptedMethods shouldContain "com.trendyol.stove.testing.e2e.grpc.test.TestService/Unary"
    }

    test("rawChannel with auth interceptor should add headers") {
      TestSystem.validate {
        grpc {
          rawChannel { ch ->
            // Create an auth interceptor that adds authorization header
            val authInterceptor = object : ClientInterceptor {
              override fun <ReqT, RespT> interceptCall(
                method: MethodDescriptor<ReqT, RespT>,
                callOptions: CallOptions,
                next: Channel
              ): ClientCall<ReqT, RespT> = object : ForwardingClientCall.SimpleForwardingClientCall<ReqT, RespT>(
                next.newCall(method, callOptions)
              ) {
                override fun start(responseListener: Listener<RespT>, headers: Metadata) {
                  headers.put(
                    Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER),
                    "Bearer raw-channel-token"
                  )
                  super.start(responseListener, headers)
                }
              }
            }

            val interceptedChannel = ClientInterceptors.intercept(ch, authInterceptor)
            val stub = TestServiceWireGrpc.TestServiceStub(interceptedChannel)

            // This should succeed because we added the auth header
            val response = stub.AuthenticatedCall(TestRequest(message = "RawAuth", count = 1))
            response.message shouldBe "Authenticated: RawAuth"
            response.success shouldBe true
          }
        }
      }
    }

    test("rawChannel with multiple interceptors should chain them") {
      val interceptorOrder = CopyOnWriteArrayList<String>()

      TestSystem.validate {
        grpc {
          rawChannel { ch ->
            val firstInterceptor = object : ClientInterceptor {
              override fun <ReqT, RespT> interceptCall(
                method: MethodDescriptor<ReqT, RespT>,
                callOptions: CallOptions,
                next: Channel
              ): ClientCall<ReqT, RespT> {
                interceptorOrder.add("first")
                return next.newCall(method, callOptions)
              }
            }

            val secondInterceptor = object : ClientInterceptor {
              override fun <ReqT, RespT> interceptCall(
                method: MethodDescriptor<ReqT, RespT>,
                callOptions: CallOptions,
                next: Channel
              ): ClientCall<ReqT, RespT> {
                interceptorOrder.add("second")
                return next.newCall(method, callOptions)
              }
            }

            // Interceptors are applied in reverse order (last added runs first)
            val interceptedChannel = ClientInterceptors.intercept(ch, firstInterceptor, secondInterceptor)
            val stub = TestServiceWireGrpc.TestServiceStub(interceptedChannel)

            stub.Unary(TestRequest(message = "Chained", count = 1))
          }
        }
      }

      // ClientInterceptors.intercept applies in reverse order
      interceptorOrder shouldBe listOf("second", "first")
    }

    test("rawChannel should allow manual stub creation with custom call options") {
      TestSystem.validate {
        grpc {
          rawChannel { ch ->
            val stub = TestServiceWireGrpc.TestServiceStub(ch)

            // Execute a call
            val response = stub.Unary(TestRequest(message = "Manual", count = 99))
            response.message shouldBe "Echo: Manual"
            response.count shouldBe 99
          }
        }
      }
    }
  })
