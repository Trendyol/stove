package com.trendyol.stove.examples.kotlin.ktor.e2e.tests.product

import arrow.core.*
import com.mongodb.client.model.Filters
import com.trendyol.stove.examples.domain.product.Product
import com.trendyol.stove.examples.domain.product.events.ProductCreatedEvent
import com.trendyol.stove.examples.kotlin.ktor.domain.product.ProductRepository
import com.trendyol.stove.examples.kotlin.ktor.e2e.setup.TestData
import com.trendyol.stove.examples.kotlin.ktor.infra.components.product.api.ProductCreateRequest
import com.trendyol.stove.functional.get
import com.trendyol.stove.recipes.shared.application.category.CategoryApiResponse
import com.trendyol.stove.testing.e2e.http.http
import com.trendyol.stove.testing.e2e.mongodb.mongodb
import com.trendyol.stove.testing.e2e.standalone.kafka.kafka
import com.trendyol.stove.testing.e2e.system.TestSystem.Companion.validate
import com.trendyol.stove.testing.e2e.system.using
import com.trendyol.stove.testing.e2e.wiremock.wiremock
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
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

      mongodb {
        shouldQuery<Product>(Filters.eq("id", productId.toString()).toBsonDocument().toJson()) { actual ->
          actual.size shouldBe 1
          actual[0].name shouldBe productName
          actual[0].price shouldBe 100.0
        }
      }

      using<ProductRepository> {
        val product = findById(productId.toString()).get()
        product.name shouldBe productName
        product.price shouldBe 100.0
        product.categoryId shouldBe categoryApiResponse.id
      }

      kafka {
        shouldBePublished<ProductCreatedEvent>(10.seconds) {
          actual.price == 100.0 && actual.name == productName
        }

        shouldBeConsumed<ProductCreatedEvent>(10.seconds) {
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
          statusCode = 200,
          responseBody = categoryApiResponse.some()
        )
      }

      http {
        val req = ProductCreateRequest(productName, 100.0, categoryApiResponse.id)
        postAndExpectBody<Any>("/products", body = req.some()) { actual ->
          actual.status shouldBe 409
        }
      }

      mongodb {
        shouldQuery<Product>(Filters.eq("id", productId.toString()).toBsonDocument().toJson()) { actual ->
          actual.size shouldBe 0
        }
      }

      using<ProductRepository> {
        findById(productId.toString()) shouldBe None
      }
    }
  }
})
