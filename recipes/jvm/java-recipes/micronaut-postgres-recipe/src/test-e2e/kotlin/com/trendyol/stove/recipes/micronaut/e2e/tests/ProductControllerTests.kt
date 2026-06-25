package com.trendyol.stove.recipes.micronaut.e2e.tests

import arrow.core.some
import com.trendyol.stove.http.http
import com.trendyol.stove.postgres.postgresql
import com.trendyol.stove.recipes.micronaut.domain.Product
import com.trendyol.stove.recipes.micronaut.domain.ProductRepository
import com.trendyol.stove.recipes.micronaut.domain.SupplierPermission
import com.trendyol.stove.recipes.micronaut.infra.api.CreateProductRequest
import com.trendyol.stove.system.stove
import com.trendyol.stove.system.using
import com.trendyol.stove.tracing.tracing
import com.trendyol.stove.wiremock.wiremock
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import java.util.UUID

class ProductControllerTests :
  FunSpec({

    test("index should be reachable") {
      stove {
        http {
          get<String>("/products/index", queryParams = mapOf("keyword" to "index")) { actual ->
            actual shouldContain "Hi from Stove framework with index"
          }
        }
      }
    }

    test("should save product to PostgreSQL when product creation request is sent") {
      val id = UUID.randomUUID().toString()
      val request = CreateProductRequest(id, "product name", 120688L)
      val supplierMock = SupplierPermission(120688L, false)

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
            actual.supplierId shouldBe 120688L
            actual.name shouldBe "product name"
          }
        }
        postgresql {
          shouldQuery<Product>(
            "SELECT * FROM products WHERE id = '${request.id}'",
            mapper = { row ->
              Product(
                row.string("id"),
                row.string("name"),
                row.long("supplier_id"),
                row.boolean("is_blacklist"),
                java.util.Date(row.sqlTimestamp("created_date").time)
              )
            }
          ) { products ->
            products.size shouldBe 1
            products.first().name shouldBe request.name
            products.first().id shouldBe request.id
            products.first().supplierId shouldBe request.supplierId
            products.first().isBlacklist shouldBe false
          }
        }
      }
    }

    test("a product persisted via the API is readable through the bridged repository") {
      val id = UUID.randomUUID().toString()
      val request = CreateProductRequest(id, "bridged product", 120689L)
      val supplierMock = SupplierPermission(120689L, false)

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
            actual.id shouldBe id
          }
        }
        using<ProductRepository> {
          this shouldNotBe null
          val persisted = findById(id).block()
          persisted shouldNotBe null
          persisted!!.name shouldBe "bridged product"
          persisted.supplierId shouldBe 120689L
        }
      }
    }

    test("tracing should capture micronaut request flow") {
      stove {
        http {
          get<String>("/products/index", queryParams = mapOf("keyword" to "trace")) { actual ->
            actual shouldContain "Hi from Stove framework with trace"
          }
        }
        tracing {
          val spans = waitForSpans(expectedCount = 1, timeoutMs = 10_000)
          spans.isNotEmpty() shouldBe true
        }
      }
    }
  })
