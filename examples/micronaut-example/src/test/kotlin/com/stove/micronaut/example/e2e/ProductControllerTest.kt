package com.stove.micronaut.example.e2e

import arrow.core.some
import com.trendyol.stove.testing.e2e.couchbase.couchbase
import com.trendyol.stove.testing.e2e.http.http
import com.trendyol.stove.testing.e2e.system.TestSystem
import com.trendyol.stove.testing.e2e.wiremock.wiremock
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import stove.micronaut.example.application.domain.Product
import stove.micronaut.example.application.services.SupplierPermission
import stove.micronaut.example.infrastructure.api.model.request.CreateProductRequest

class ProductControllerTest :
  FunSpec({

    test("index should be reachable") {
      TestSystem.validate {
        http {
          get<String>("/products/index", queryParams = mapOf("keyword" to "index")) { actual ->
            actual shouldContain "Hi from Stove framework with index"
            println(actual)
          }
        }
      }
    }

    test("should create a product in Couchbase when a product is created") {
      val id = "RANDOM0001"
      val request = CreateProductRequest(id = id, name = "Deneme", supplierId = 120688)
      val supplierMock = SupplierPermission(id = 120688, isBlacklisted = false)

      TestSystem.validate {

        wiremock {
          mockGet(
            "/v2/suppliers/${supplierMock.id}?storeFrontId=1",
            statusCode = 200,
            responseBody = supplierMock.some()
          )
        }
        http {
          postAndExpectJson<Product>("/products/create", body = request.some()) { actual ->
            actual.supplierId shouldBe 120688
            actual.name shouldBe "Deneme"
          }
        }
        couchbase {
          shouldGet<Product>(request.id) {
            it.name shouldBe request.name
            it.id shouldBe request.id
            it.supplierId shouldBe request.supplierId
            it.isBlacklist shouldBe false
          }
        }
      }
    }
  })
