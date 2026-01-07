package com.trendyol.stove.examples.kotlin.ktor.e2e.tests.product

import arrow.core.*
import com.trendyol.stove.examples.domain.product.Product
import com.trendyol.stove.examples.domain.product.events.ProductCreatedEvent
import com.trendyol.stove.examples.kotlin.ktor.domain.product.ProductRepository
import com.trendyol.stove.examples.kotlin.ktor.e2e.setup.*
import com.trendyol.stove.examples.kotlin.ktor.infra.components.product.api.ProductCreateRequest
import com.trendyol.stove.examples.kotlin.ktor.infra.components.product.persistency.ProductTable
import com.trendyol.stove.functional.get
import com.trendyol.stove.http.http
import com.trendyol.stove.kafka.kafka
import com.trendyol.stove.postgres.postgresql
import com.trendyol.stove.recipes.shared.application.category.CategoryApiResponse
import com.trendyol.stove.system.*
import com.trendyol.stove.wiremock.wiremock
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
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
            "SELECT * FROM ${ProductTable.tableName} WHERE ${ProductTable.id.name} = '$productId'",
            ProductFrom::invoke
          ) { actual ->
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

        postgresql {
          shouldQuery<Product>(
            "SELECT * FROM ${ProductTable.tableName} WHERE ${ProductTable.id.name} = '$productId'",
            ProductFrom::invoke
          ) { actual ->
            actual.size shouldBe 0
          }
        }

        using<ProductRepository> {
          findById(productId.toString()) shouldBe None
        }
      }
    }
  })
