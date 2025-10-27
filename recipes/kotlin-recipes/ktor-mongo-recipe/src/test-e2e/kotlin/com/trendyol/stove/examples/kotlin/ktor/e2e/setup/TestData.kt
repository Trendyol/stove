package com.trendyol.stove.examples.kotlin.ktor.e2e.setup

object TestData {
  object Random {
    fun positiveInt() = kotlin.random.Random.nextInt(1, Int.MAX_VALUE)
  }
}
