package com.trendyol.stove.kafka

import com.github.benmanes.caffeine.cache.*

object Caching {
  fun <K : Any, V : Any> of(): Cache<K, V> = Caffeine.newBuilder().build()
}
