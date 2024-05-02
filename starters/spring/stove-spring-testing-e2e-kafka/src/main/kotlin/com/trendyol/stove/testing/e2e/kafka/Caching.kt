package com.trendyol.stove.testing.e2e.kafka

import com.github.benmanes.caffeine.cache.*

object Caching {
  fun <K, V> of(): Cache<K, V> = Caffeine.newBuilder().build()
}
