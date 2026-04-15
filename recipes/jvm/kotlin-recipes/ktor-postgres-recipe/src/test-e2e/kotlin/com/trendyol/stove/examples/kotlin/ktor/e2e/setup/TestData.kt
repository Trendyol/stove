package com.trendyol.stove.examples.kotlin.ktor.e2e.setup

import com.trendyol.stove.examples.domain.product.Product
import kotliquery.Row

object TestData {
  object Random {
    fun positiveInt() = kotlin.random.Random.nextInt(1, Int.MAX_VALUE)
  }
}

object ProductFrom {
  operator fun invoke(row: Row): Product = Product.fromPersistency(
    row.string("id"),
    row.string("name"),
    row.double("price"),
    row.int("category_id"),
    row.long("version")
  )
}
