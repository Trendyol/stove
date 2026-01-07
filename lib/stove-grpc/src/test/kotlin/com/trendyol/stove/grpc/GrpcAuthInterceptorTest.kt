package com.trendyol.stove.grpc

import com.squareup.wire.*
import com.trendyol.stove.grpc.test.*
import com.trendyol.stove.system.stove
import io.grpc.*
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.slf4j.LoggerFactory

/**
 * Tests for authentication and interceptor functionality in GrpcSystem.
 */
class GrpcAuthInterceptorTest :
  FunSpec({

    test("authenticated call should fail without authorization header (Wire client)") {
      // The default setup has auth headers, but we can verify the error handling
      // by testing a fresh gRPC client without the headers
      stove {
        grpc {
          // Create a wire client without auth headers
          withEndpoint({ host, port ->
            val okHttpClient = okhttp3.OkHttpClient
              .Builder()
              .protocols(listOf(okhttp3.Protocol.H2_PRIOR_KNOWLEDGE))
              .build()
            com.squareup.wire.GrpcClient
              .Builder()
              .client(okHttpClient)
              .baseUrl("http://$host:$port")
              .build()
              .create(TestServiceClient::class)
          }) {
            val exception = shouldThrow<GrpcException> {
              AuthenticatedCall().execute(TestRequest(message = "Hello", count = 1))
            }
            exception.grpcStatus shouldBe GrpcStatus.UNAUTHENTICATED
            exception.grpcMessage shouldContain "authorization"
          }
        }
      }
    }

    test("wire client with auth header should succeed") {
      stove {
        grpc {
          withEndpoint({ host, port ->
            val okHttpClient = okhttp3.OkHttpClient
              .Builder()
              .protocols(listOf(okhttp3.Protocol.H2_PRIOR_KNOWLEDGE))
              .addInterceptor { chain ->
                val request = chain
                  .request()
                  .newBuilder()
                  .addHeader("authorization", "Bearer my-token")
                  .build()
                chain.proceed(request)
              }.build()
            com.squareup.wire.GrpcClient
              .Builder()
              .client(okHttpClient)
              .baseUrl("http://$host:$port")
              .build()
              .create(TestServiceClient::class)
          }) {
            val response = AuthenticatedCall().execute(TestRequest(message = "Secure", count = 42))
            response.message shouldBe "Authenticated: Secure"
            response.success shouldBe true
          }
        }
      }
    }
  })

/**
 * A logging interceptor for testing purposes.
 */
class TestLoggingInterceptor : ClientInterceptor {
  private val logger = LoggerFactory.getLogger(javaClass)

  override fun <ReqT, RespT> interceptCall(
    method: MethodDescriptor<ReqT, RespT>,
    callOptions: CallOptions,
    next: Channel
  ): ClientCall<ReqT, RespT> {
    logger.info("gRPC call: ${method.fullMethodName}")
    return next.newCall(method, callOptions)
  }
}
