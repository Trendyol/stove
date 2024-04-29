package com.trendyol.stove.testing.e2e.standalone.kafka

import java.util.Properties

fun <K, V> Map<K, V>.toProperties(): Properties =
  Properties().apply {
    this@toProperties.forEach { (k, v) -> this[k] = v }
  }
