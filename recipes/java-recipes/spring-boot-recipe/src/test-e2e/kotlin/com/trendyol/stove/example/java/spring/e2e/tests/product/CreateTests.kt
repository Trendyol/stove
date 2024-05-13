package com.trendyol.stove.example.java.spring.e2e.tests.product

import arrow.core.some
import com.trendyol.stove.example.java.spring.e2e.setup.TestData
import com.trendyol.stove.examples.domain.product.Product
import com.trendyol.stove.examples.domain.product.events.ProductCreatedEvent
import com.trendyol.stove.examples.java.spring.infra.components.product.api.ProductCreateRequest
import com.trendyol.stove.examples.java.spring.infra.components.product.persistency.CollectionConstants
import com.trendyol.stove.recipes.shared.application.category.CategoryApiResponse
import com.trendyol.stove.testing.e2e.couchbase.couchbase
import com.trendyol.stove.testing.e2e.http.http
import com.trendyol.stove.testing.e2e.standalone.kafka.kafka
import com.trendyol.stove.testing.e2e.system.TestSystem.Companion.validate
import com.trendyol.stove.testing.e2e.wiremock.wiremock
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.springframework.http.HttpStatus
import java.util.*
import kotlin.time.Duration.Companion.seconds

class CreateTests : FunSpec({
  test("product can be created with valid category") {
    validate {
      val productName = TestData.Random.positiveInt().toString()
      val productId = UUID.nameUUIDFromBytes(productName.toByteArray())

      val categoryApiResponse = CategoryApiResponse(
        TestData.Random.positiveInt(),
        "category-name",
        true
      )

      wiremock {
        mockGet(
          url = "/categories/${categoryApiResponse.id}",
          statusCode = 200,
          responseBody = categoryApiResponse.some()
        )
      }

      http {
        val req = ProductCreateRequest(productName, 100.0, categoryApiResponse.id)
        postAndExpectBody<Any>("/products", body = req.some()) { actual ->
          actual.status shouldBe 200
        }
      }

      couchbase {
        shouldGet<Product>(CollectionConstants.PRODUCT_COLLECTION, productId.toString()) { actual ->
          actual.id shouldBe productId.toString()
          actual.name shouldBe productName
          actual.price shouldBe 100.0
        }
      }

      kafka {
        shouldBePublished<ProductCreatedEvent>(10.seconds) {
          actual.price == 100.0 && actual.name == productName
        }

        shouldBeConsumed<ProductCreatedEvent> {
          actual.price == 100.0 && actual.name == productName
        }
      }
    }
  }

  test("when category is not active, product creation should fail") {
    validate {
      val productName = TestData.Random.positiveInt().toString()
      val productId = UUID.nameUUIDFromBytes(productName.toByteArray())
      val categoryApiResponse = CategoryApiResponse(
        TestData.Random.positiveInt(),
        "category-name",
        false
      )

      wiremock {
        mockGet(
          url = "/categories/${categoryApiResponse.id}",
          statusCode = 200
        )
      }

      http {
        val req = ProductCreateRequest(productName, 100.0, categoryApiResponse.id)
        postAndExpectBody<Any>("/products", body = req.some()) { actual ->
          actual.status shouldBe HttpStatus.CONFLICT.value()
        }
      }

      couchbase {
        shouldNotExist(CollectionConstants.PRODUCT_COLLECTION, productId.toString())
      }
    }
  }
})
