package com.trendyol.stove.examples.domain

import com.trendyol.stove.examples.domain.product.Product
import com.trendyol.stove.examples.domain.product.events.*
import com.trendyol.stove.examples.domain.testing.aggregateroot.AggregateRootAssertion.Companion.assertEvents
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class ProductTests : FunSpec({
  test("should create product") {
    // Given
    val product = Product.create("Product 1", 100.0)

    // Then
    product.name shouldBe "Product 1"
    product.price shouldBe 100.0
  }

  test("when price change") {
    // Given
    val product = Product.create("Product 1", 100.0)

    // When
    product.changePrice(200.0)

    // Then
    product.version shouldBe 2
    assertEvents(product) {
      shouldContain<ProductPriceChangedEvent> {
        newPrice shouldBe 200.0
      }
      shouldContain<ProductCreatedEvent> {
        name shouldBe "Product 1"
        price shouldBe 100.0
      }

      shouldNotContain<ProductNameChangedEvent>()
    }
  }

  test("change name") {
    // Given
    val product = Product.create("Product 1", 100.0)

    // When
    product.changeName("Product 2")

    // Then
    product.version shouldBe 2
    assertEvents(product) {
      shouldContain<ProductNameChangedEvent> {
        newName shouldBe "Product 2"
      }
      shouldContain<ProductCreatedEvent> {
        name shouldBe "Product 1"
        price shouldBe 100.0
      }

      shouldNotContain<ProductPriceChangedEvent>()
    }
  }
})
