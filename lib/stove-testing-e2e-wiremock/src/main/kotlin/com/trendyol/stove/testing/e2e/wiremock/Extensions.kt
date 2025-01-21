package com.trendyol.stove.testing.e2e.wiremock

import com.github.benmanes.caffeine.cache.Cache

fun <K : Any, V : Any> Cache<K, V>.containsKey(key: K): Boolean = this.getIfPresent(key) != null
