package com.stove.micronaut.example.e2e

import arrow.core.some
import com.couchbase.client.java.Bucket
import com.trendyol.stove.couchbase.couchbase
import com.trendyol.stove.http.http
import com.trendyol.stove.system.*
import com.trendyol.stove.wiremock.wiremock
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import stove.micronaut.example.application.domain.Product
import stove.micronaut.example.application.services.SupplierPermission
import stove.micronaut.example.infrastructure.api.model.request.CreateProductRequest
import java.util.*

class ProductControllerTest :
  FunSpec({

    test("index should be reachable") {
      stove {
        http {
          get<String>("/products/index", queryParams = mapOf("keyword" to "index")) { actual ->
            actual shouldContain "Hi from Stove framework with index"
            println(actual)
          }
        }
      }
    }

    test("should save product to Couchbase when product creation request is sent") {
      val id = UUID.randomUUID().toString()
      val request = CreateProductRequest(id = id, name = "product name", supplierId = 120688)
      val supplierMock = SupplierPermission(id = 120688, isBlacklisted = false)

      stove {

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
            actual.name shouldBe "product name"
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

    test("a bean from application should be reachable") {
      stove {
        using<Bucket> {
          this.name() shouldBe "Stove"
        }
      }
    }
  })
