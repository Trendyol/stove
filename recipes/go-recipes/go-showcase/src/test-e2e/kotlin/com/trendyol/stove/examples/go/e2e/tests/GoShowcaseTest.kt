package com.trendyol.stove.examples.go.e2e.tests

import arrow.core.some
import com.trendyol.stove.http.http
import com.trendyol.stove.kafka.kafka
import com.trendyol.stove.postgres.postgresql
import com.trendyol.stove.system.stove
import com.trendyol.stove.tracing.tracing
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotliquery.Row
import kotlin.time.Duration.Companion.seconds

data class CreateProductRequest(
  val name: String,
  val price: Double
)

data class ProductResponse(
  val id: String,
  val name: String,
  val price: Double
)

data class ProductRow(
  val id: String,
  val name: String,
  val price: Double
)

data class ProductCreatedEvent(
  val id: String,
  val name: String,
  val price: Double
)

data class ProductUpdateEvent(
  val id: String,
  val name: String,
  val price: Double
)

private val productRowMapper: (Row) -> ProductRow = { row ->
  ProductRow(
    id = row.string("id"),
    name = row.string("name"),
    price = row.double("price")
  )
}

class GoShowcaseTest :
  FunSpec({

    test("should create a product and verify via HTTP, database, and traces") {
      stove {
        val productName = "Stove Go Showcase Product"
        val productPrice = 42.99
        var productId: String? = null

        // 1. Create a product via the Go application's REST API
        http {
          postAndExpectBody<ProductResponse>(
            uri = "/api/products",
            body = CreateProductRequest(name = productName, price = productPrice).some()
          ) { actual ->
            actual.status shouldBe 201
            productId = actual.body().id
            actual.body().name shouldBe productName
            actual.body().price shouldBe productPrice
          }
        }

        // 2. Verify the product was persisted in PostgreSQL
        postgresql {
          shouldQuery<ProductRow>(
            query = "SELECT id, name, price FROM products WHERE id = '$productId'",
            mapper = productRowMapper
          ) { rows ->
            rows.size shouldBe 1
            rows.first().name shouldBe productName
            rows.first().price shouldBe productPrice
          }
        }

        // 3. Read the product back via HTTP
        http {
          getResponse<ProductResponse>(
            uri = "/api/products/$productId"
          ) { actual ->
            actual.status shouldBe 200
            actual.body().id shouldBe productId
            actual.body().name shouldBe productName
          }
        }

        // 4. Verify traces — spans are auto-created by otelhttp middleware and otelsql driver
        tracing {
          waitForSpans(4, 5000)
          shouldContainSpan("http.request")
          shouldNotHaveFailedSpans()
          spanCountShouldBeAtLeast(4)
          executionTimeShouldBeLessThan(10.seconds)
        }
      }
    }

    test("should list all products") {
      stove {
        http {
          postAndExpectBody<ProductResponse>(
            uri = "/api/products",
            body = CreateProductRequest(name = "Product A", price = 10.0).some()
          ) { actual ->
            actual.body().name shouldBe "Product A"
          }
        }

        http {
          postAndExpectBody<ProductResponse>(
            uri = "/api/products",
            body = CreateProductRequest(name = "Product B", price = 20.0).some()
          ) { actual ->
            actual.body().name shouldBe "Product B"
          }
        }

        http {
          getMany<ProductResponse>(
            uri = "/api/products"
          ) { actual ->
            actual.size shouldNotBe 0
          }
        }

        tracing {
          waitForSpans(4, 5000)
          shouldContainSpan("http.request")
          shouldNotHaveFailedSpans()
        }
      }
    }

    test("should return 404 for non-existent product") {
      stove {
        http {
          getBodilessResponse("/api/products/non-existent-id") { actual ->
            actual.status shouldBe 404
          }
        }
      }
    }

    test("should publish ProductCreatedEvent when product is created") {
      stove {
        http {
          postAndExpectBody<ProductResponse>(
            uri = "/api/products",
            body = CreateProductRequest(name = "Kafka Product", price = 29.99).some()
          ) { actual ->
            actual.status shouldBe 201
            actual.body().name shouldBe "Kafka Product"
          }
        }

        kafka {
          shouldBePublished<ProductCreatedEvent>(10.seconds) {
            actual.name == "Kafka Product" && actual.price == 29.99
          }
        }
      }
    }

    test("should consume product update events from Kafka") {
      stove {
        var productId: String? = null

        // First create a product via HTTP
        http {
          postAndExpectBody<ProductResponse>(
            uri = "/api/products",
            body = CreateProductRequest(name = "Original Name", price = 10.0).some()
          ) { actual ->
            actual.status shouldBe 201
            productId = actual.body().id
          }
        }

        // Publish an update event to Kafka — Go consumer picks it up and updates DB
        kafka {
          publish("product.update", ProductUpdateEvent(id = productId!!, name = "Updated Name", price = 99.99))
          shouldBeConsumed<ProductUpdateEvent>(10.seconds) {
            actual.id == productId && actual.name == "Updated Name"
          }
        }

        // Verify the product was updated in the database
        postgresql {
          shouldQuery<ProductRow>(
            query = "SELECT id, name, price FROM products WHERE id = '$productId'",
            mapper = productRowMapper
          ) { rows ->
            rows.size shouldBe 1
            rows.first().name shouldBe "Updated Name"
            rows.first().price shouldBe 99.99
          }
        }
      }
    }
  })
