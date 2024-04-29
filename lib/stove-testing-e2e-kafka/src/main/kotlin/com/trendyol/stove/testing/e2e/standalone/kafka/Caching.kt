package com.trendyol.stove.testing.e2e.standalone.kafka

import com.github.benmanes.caffeine.cache.*
import kotlinx.coroutines.coroutineScope

object Caching {
  fun <K, V> of(): Cache<K, V> = Caffeine.newBuilder().build()

  class Wrapper<T, K, V>(private val raw: T) {
    private val cache = of<K, V>()

    suspend fun byCaching(key: K, block: T.() -> V): V = coroutineScope {
      cache.get(key) { block(raw) }
    }
  }
}
