package com.trendyol.stove.wiremock

import com.github.benmanes.caffeine.cache.Cache

fun <K : Any, V : Any> Cache<K, V>.containsKey(key: K): Boolean = this.getIfPresent(key) != null
