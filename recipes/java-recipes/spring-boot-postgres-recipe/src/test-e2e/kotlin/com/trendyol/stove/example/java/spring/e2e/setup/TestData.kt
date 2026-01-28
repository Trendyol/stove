package com.trendyol.stove.example.java.spring.e2e.setup

object TestData {
  object Random {
    fun positiveInt() = kotlin.random.Random.nextInt(1, Int.MAX_VALUE)
  }
}
