package com.trendyol.stove.testing.e2e.wiremock

import com.github.benmanes.caffeine.cache.Cache

fun <K, V> Cache<K, V>.containsKey(key: K): Boolean = this.getIfPresent(key) != null
