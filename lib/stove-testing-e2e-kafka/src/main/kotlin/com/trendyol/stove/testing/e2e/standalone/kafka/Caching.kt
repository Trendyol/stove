package com.trendyol.stove.testing.e2e.standalone.kafka

import com.github.benmanes.caffeine.cache.*

object Caching {
  fun <K, V> of(): Cache<K, V> = Caffeine.newBuilder().build()
}
