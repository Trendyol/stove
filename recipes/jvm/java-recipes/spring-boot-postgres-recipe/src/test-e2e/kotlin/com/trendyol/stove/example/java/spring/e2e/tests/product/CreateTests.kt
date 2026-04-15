package com.trendyol.stove.example.java.spring.e2e.tests.product

import arrow.core.some
import com.trendyol.stove.example.java.spring.e2e.setup.TestData
import com.trendyol.stove.examples.domain.product.Product
import com.trendyol.stove.examples.domain.product.events.ProductCreatedEvent
import com.trendyol.stove.examples.java.spring.domain.ProductReactiveRepository
import com.trendyol.stove.examples.java.spring.infra.components.product.api.ProductCreateRequest
import com.trendyol.stove.http.http
import com.trendyol.stove.kafka.kafka
import com.trendyol.stove.postgres.postgresql
import com.trendyol.stove.recipes.shared.application.category.CategoryApiResponse
import com.trendyol.stove.system.*
import com.trendyol.stove.wiremock.wiremock
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.springframework.http.HttpStatus
import java.util.*
import kotlin.time.Duration.Companion.seconds

class CreateTests :
  FunSpec({
    test("product can be created with valid category") {
      stove {
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

        postgresql {
          shouldQuery<Product>(
            "SELECT * FROM products WHERE id = '$productId'",
            mapper = { row ->
              Product.fromPersistency(
                row.string("id"),
                row.string("name"),
                row.double("price"),
                row.int("category_id"),
                row.long("version")
              )
            }
          ) { products ->
            products.size shouldBe 1
            products.first().id shouldBe productId.toString()
            products.first().name shouldBe productName
            products.first().price shouldBe 100.0
          }
        }

        using<ProductReactiveRepository> {
          val product = findById(productId.toString()).toFuture().get()
          product.name shouldBe productName
          product.price shouldBe 100.0
          product.categoryId shouldBe categoryApiResponse.id
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
      stove {
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

        postgresql {
          shouldQuery<Product>(
            "SELECT * FROM products WHERE id = '$productId'",
            mapper = { row ->
              Product.fromPersistency(
                row.string("id"),
                row.string("name"),
                row.double("price"),
                row.int("category_id"),
                row.long("version")
              )
            }
          ) { products ->
            products.size shouldBe 0
          }
        }
      }
    }
  })
