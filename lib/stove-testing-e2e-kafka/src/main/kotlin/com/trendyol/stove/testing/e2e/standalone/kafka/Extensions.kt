package com.trendyol.stove.testing.e2e.standalone.kafka

import com.trendyol.stove.testing.e2e.messaging.MessageMetadata
import java.util.*

fun <K, V> Map<K, V>.toProperties(): Properties =
  Properties().apply {
    this@toProperties.forEach { (k, v) -> this[k] = v }
  }

fun ConsumedMessage.metadata(): MessageMetadata = MessageMetadata(
  topic = topic,
  key = key,
  headers = headers
)
