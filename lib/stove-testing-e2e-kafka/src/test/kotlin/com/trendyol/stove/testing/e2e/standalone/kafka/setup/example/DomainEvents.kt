package com.trendyol.stove.testing.e2e.standalone.kafka.setup.example

import kotlin.random.Random

object DomainEvents {
  data class ProductCreated(
    val productId: String
  ) {
    companion object {
      val randomString = { Random.nextInt(0, Int.MAX_VALUE).toString() }

      fun randoms(count: Int): List<ProductCreated> = (0 until count).map { ProductCreated(randomString()) }
    }
  }

  data class ProductFailingCreated(
    val productId: String
  )
}
