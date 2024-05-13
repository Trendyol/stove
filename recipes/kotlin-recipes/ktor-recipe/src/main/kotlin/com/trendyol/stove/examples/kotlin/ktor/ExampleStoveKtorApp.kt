package com.trendyol.stove.examples.kotlin.ktor

import com.trendyol.stove.examples.domain.product.Product

class ExampleStoveKtorApp

fun main() {
  val product = Product.create("Product 1", 100.0)
  println("Hello, Stove!, Product: $product")
}
