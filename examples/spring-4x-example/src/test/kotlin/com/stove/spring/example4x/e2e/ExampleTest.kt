package com.stove.spring.example4x.e2e

import arrow.core.some
import com.trendyol.stove.testing.e2e.http.*
import com.trendyol.stove.testing.e2e.kafka.kafka
import com.trendyol.stove.testing.e2e.system.*
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import stove.spring.example4x.application.handlers.ProductCreatedEvent
import stove.spring.example4x.infrastructure.api.ProductCreateRequest
import stove.spring.example4x.infrastructure.messaging.kafka.CreateProductCommand
import kotlin.time.Duration.Companion.seconds

class ExampleTest :
  FunSpec({
    test("index should be reachable") {
      TestSystem.validate {
        http {
          get<String>("/api/index", queryParams = mapOf("keyword" to testCase.name.name)) { actual ->
            actual shouldContain "Hi from Stove framework with ${testCase.name.name}"
            println(actual)
          }
          get<String>("/api/index") { actual ->
            actual shouldContain "Hi from Stove framework with"
            println(actual)
          }
        }
      }
    }

    test("should create new product when send product create request from api") {
      TestSystem.validate {
        val productCreateRequest = ProductCreateRequest(1L, name = "product name", 99L)

        http {
          postAndExpectBodilessResponse(uri = "/api/product/create", body = productCreateRequest.some()) { actual ->
            actual.status shouldBe 200
          }
        }

        kafka {
          shouldBePublished<ProductCreatedEvent> {
            actual.id == productCreateRequest.id &&
              actual.name == productCreateRequest.name &&
              actual.supplierId == productCreateRequest.supplierId
          }
        }
      }
    }

    test("should consume product create command from kafka") {
      TestSystem.validate {
        val createProductCommand = CreateProductCommand(2L, name = "product from kafka", 100L)

        kafka {
          publish("trendyol.stove.service.product.create.0", createProductCommand)
          shouldBeConsumed<CreateProductCommand>(10.seconds) {
            actual.id == createProductCommand.id &&
              actual.name == createProductCommand.name &&
              actual.supplierId == createProductCommand.supplierId
          }
        }
      }
    }
  })
