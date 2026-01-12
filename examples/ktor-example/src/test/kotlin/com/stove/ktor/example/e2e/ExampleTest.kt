package com.stove.ktor.example.e2e

import arrow.core.*
import com.trendyol.stove.http.http
import com.trendyol.stove.kafka.kafka
import com.trendyol.stove.postgres.postgresql
import com.trendyol.stove.system.stove
import com.trendyol.stove.system.using
import com.trendyol.stove.testing.grpcmock.grpcMock
import io.grpc.Status
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import stove.ktor.example.application.*
import stove.ktor.example.domain.*
import stove.ktor.example.grpc.*
import kotlin.random.Random
import kotlin.time.Duration.Companion.seconds

class ExampleTest :
  FunSpec({
    data class ProductOfTest(
      val id: Long,
      val name: String
    )

    test("should save product when both Feature Toggle and Pricing services respond successfully") {
      stove {
        val givenId = Random.nextInt()
        val givenName = "T-Shirt, Red, M"
        val givenUserId = "user-123"
        val expectedPrice = 29.99

        // =====================================================
        // Mock MULTIPLE gRPC services in the SAME grpcMock block
        // All services are handled by the same mock server
        // =====================================================
        grpcMock {
          // Mock #1: Feature Toggle Service - enable the feature
          mockUnary(
            serviceName = "featuretoggle.FeatureToggleService",
            methodName = "IsFeatureEnabled",
            response = IsFeatureEnabledResponse
              .newBuilder()
              .setEnabled(true)
              .setVariant("default")
              .build()
          )

          // Mock #2: Pricing Service - return calculated price
          mockUnary(
            serviceName = "pricing.PricingService",
            methodName = "CalculatePrice",
            response = CalculatePriceResponse
              .newBuilder()
              .setBasePrice(34.99)
              .setDiscount(5.00)
              .setFinalPrice(expectedPrice)
              .setCurrency("USD")
              .build()
          )
        }

        postgresql {
          shouldExecute(
            """
            DROP TABLE IF EXISTS Products;
            CREATE TABLE IF NOT EXISTS Products (
            	id serial PRIMARY KEY,
            	name VARCHAR (50)  NOT NULL
            );
            """.trimIndent()
          )
          shouldExecute("INSERT INTO Products (id, name) VALUES ('$givenId', 'T-Shirt, Red, S')")
        }

        http {
          postAndExpectBodilessResponse(
            "/products/$givenId",
            body = UpdateProductRequest(givenName, givenUserId).some(),
            token = None
          ) { actual ->
            actual.status shouldBe 200
          }
        }

        postgresql {
          shouldQuery<ProductOfTest>("Select * FROM Products WHERE id=$givenId", mapper = { row ->
            ProductOfTest(row.long("id"), row.string("name"))
          }) {
            it.count() shouldBe 1
            it.first().name shouldBe givenName
          }
        }

        kafka {
          shouldBePublished<DomainEvents.ProductUpdated>(5.seconds) {
            actual.id == givenId && actual.name == givenName && actual.price == expectedPrice
          }
          shouldBeConsumed<DomainEvents.ProductUpdated>(20.seconds) {
            actual.id == givenId && actual.name == givenName
          }
        }
      }
    }

    test("should reject update when Feature Toggle is disabled (Pricing not called)") {
      stove {
        val givenId = Random.nextInt()
        val givenName = "T-Shirt, Blue, L"
        val givenUserId = "user-456"

        grpcMock {
          // Feature Toggle disabled - Pricing service won't be called
          mockUnary(
            serviceName = "featuretoggle.FeatureToggleService",
            methodName = "IsFeatureEnabled",
            response = IsFeatureEnabledResponse
              .newBuilder()
              .setEnabled(false)
              .build()
          )
          // Note: No Pricing mock needed - feature check fails first
        }

        postgresql {
          shouldExecute(
            """
            DROP TABLE IF EXISTS Products;
            CREATE TABLE IF NOT EXISTS Products (
            	id serial PRIMARY KEY,
            	name VARCHAR (50)  NOT NULL
            );
            """.trimIndent()
          )
          shouldExecute("INSERT INTO Products (id, name) VALUES ('$givenId', 'T-Shirt, Blue, S')")
        }

        http {
          postAndExpectBodilessResponse(
            "/products/$givenId",
            body = UpdateProductRequest(givenName, givenUserId).some(),
            token = None
          ) { actual ->
            actual.status shouldBe 400
          }
        }

        // Verify product was NOT updated
        postgresql {
          shouldQuery<ProductOfTest>("Select * FROM Products WHERE id=$givenId", mapper = { row ->
            ProductOfTest(row.long("id"), row.string("name"))
          }) {
            it.first().name shouldBe "T-Shirt, Blue, S"
          }
        }
      }
    }

    test("should handle Pricing Service failure gracefully") {
      stove {
        val givenId = Random.nextInt()
        val givenName = "T-Shirt, Green, XL"
        val givenUserId = "user-789"

        grpcMock {
          // Feature Toggle enabled
          mockUnary(
            serviceName = "featuretoggle.FeatureToggleService",
            methodName = "IsFeatureEnabled",
            response = IsFeatureEnabledResponse
              .newBuilder()
              .setEnabled(true)
              .build()
          )

          // Pricing Service returns error
          mockError(
            serviceName = "pricing.PricingService",
            methodName = "CalculatePrice",
            status = Status.Code.UNAVAILABLE,
            message = "Pricing service is temporarily unavailable"
          )
        }

        postgresql {
          shouldExecute(
            """
            DROP TABLE IF EXISTS Products;
            CREATE TABLE IF NOT EXISTS Products (
            	id serial PRIMARY KEY,
            	name VARCHAR (50)  NOT NULL
            );
            """.trimIndent()
          )
          shouldExecute("INSERT INTO Products (id, name) VALUES ('$givenId', 'T-Shirt, Green, S')")
        }

        http {
          postAndExpectBodilessResponse(
            "/products/$givenId",
            body = UpdateProductRequest(givenName, givenUserId).some(),
            token = None
          ) { actual ->
            // Should fail because pricing service is unavailable
            actual.status shouldBe 400
          }
        }
      }
    }

    test("should mock different responses for same service based on request matching") {
      stove {
        val givenId = Random.nextInt()
        val givenName = "Premium T-Shirt"
        val givenUserId = "vip-user"

        grpcMock {
          // Feature Toggle - enabled
          mockUnary(
            serviceName = "featuretoggle.FeatureToggleService",
            methodName = "IsFeatureEnabled",
            response = IsFeatureEnabledResponse
              .newBuilder()
              .setEnabled(true)
              .setVariant("premium")
              .build()
          )

          // Pricing - VIP price with bigger discount
          mockUnary(
            serviceName = "pricing.PricingService",
            methodName = "CalculatePrice",
            response = CalculatePriceResponse
              .newBuilder()
              .setBasePrice(99.99)
              .setDiscount(30.00) // VIP gets 30% off
              .setFinalPrice(69.99)
              .setCurrency("USD")
              .build()
          )
        }

        postgresql {
          shouldExecute(
            """
            DROP TABLE IF EXISTS Products;
            CREATE TABLE IF NOT EXISTS Products (
              id serial PRIMARY KEY,
              name VARCHAR (50) NOT NULL
            );
            """.trimIndent()
          )
          shouldExecute("INSERT INTO Products (id, name) VALUES ('$givenId', 'Old Name')")
        }

        http {
          postAndExpectBodilessResponse(
            "/products/$givenId",
            body = UpdateProductRequest(givenName, givenUserId).some(),
            token = None
          ) { actual ->
            actual.status shouldBe 200
          }
        }

        kafka {
          shouldBePublished<DomainEvents.ProductUpdated>(5.seconds) {
            actual.price == 69.99 // VIP price
          }
        }
      }
    }

    test("stove should be able to override the test deps") {
      stove {
        using<LockProvider> {
          (this is NoOpLockProvider) shouldBe true
        }
      }
    }
  })
