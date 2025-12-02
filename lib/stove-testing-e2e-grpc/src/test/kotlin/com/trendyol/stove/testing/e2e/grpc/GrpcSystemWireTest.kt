package com.trendyol.stove.testing.e2e.grpc

import com.trendyol.stove.testing.e2e.grpc.test.*
import com.trendyol.stove.testing.e2e.system.TestSystem
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith

/**
 * Tests for Wire client functionality in GrpcSystem.
 */
class GrpcSystemWireTest :
  FunSpec({

    test("wireClient should execute unary call successfully") {
      TestSystem.validate {
        grpc {
          wireClient<TestServiceClient> {
            val response = Unary().execute(TestRequest(message = "Hello Wire", count = 42))
            response.message shouldBe "Echo: Hello Wire"
            response.count shouldBe 42
            response.success shouldBe true
          }
        }
      }
    }

    test("rawWireClient should provide direct access to GrpcClient") {
      TestSystem.validate {
        grpc {
          rawWireClient { client ->
            val service = client.create(TestServiceClient::class)
            val response = service.Unary().execute(TestRequest(message = "Direct", count = 1))
            response.message shouldStartWith "Echo:"
          }
        }
      }
    }

    test("withEndpoint should work with Wire client factory") {
      TestSystem.validate {
        grpc {
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
            val response = Unary().execute(TestRequest(message = "Custom", count = 99))
            response.message shouldBe "Echo: Custom"
            response.count shouldBe 99
          }
        }
      }
    }

    test("multiple sequential calls should work") {
      TestSystem.validate {
        grpc {
          wireClient<TestServiceClient> {
            val response1 = Unary().execute(TestRequest(message = "First", count = 1))
            response1.message shouldBe "Echo: First"

            val response2 = Unary().execute(TestRequest(message = "Second", count = 2))
            response2.message shouldBe "Echo: Second"

            val response3 = Unary().execute(TestRequest(message = "Third", count = 3))
            response3.message shouldBe "Echo: Third"
          }
        }
      }
    }
  })
