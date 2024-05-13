package com.trendyol.stove.example.java.spring.e2e.tests

import arrow.core.some
import com.trendyol.stove.examples.domain.product.Product
import com.trendyol.stove.examples.domain.product.events.ProductCreatedEvent
import com.trendyol.stove.examples.java.spring.infra.components.product.api.ProductCreateRequest
import com.trendyol.stove.examples.java.spring.infra.components.product.persistency.CollectionConstants
import com.trendyol.stove.testing.e2e.couchbase.couchbase
import com.trendyol.stove.testing.e2e.http.http
import com.trendyol.stove.testing.e2e.standalone.kafka.kafka
import com.trendyol.stove.testing.e2e.system.TestSystem.Companion.validate
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.util.*
import kotlin.time.Duration.Companion.seconds

class ProductTests : FunSpec({
  test("product can be created") {
    validate {
      val productName = "product-name"
      val productId = UUID.nameUUIDFromBytes(productName.toByteArray())
      http {
        val req = ProductCreateRequest(productName, 100.0)
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
})
