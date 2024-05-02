package com.trendyol.stove.testing.e2e.kafka

import com.trendyol.stove.testing.e2e.messaging.MessageMetadata
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.producer.ProducerRecord

internal fun <K, V : Any> ProducerRecord<K, V>.toMetadata(): MessageMetadata = MessageMetadata(
  this.topic(),
  this.key().toString(),
  this.headers().associate { h -> Pair(h.key(), String(h.value())) }
)

internal fun <K, V : Any> ConsumerRecord<K, V>.toMetadata(): MessageMetadata = MessageMetadata(
  this.topic(),
  this.key().toString(),
  this.headers().associate { h -> Pair(h.key(), String(h.value())) }
)

data class StoveConsumedMessage(
  val topic: String,
  val value: String,
  val offset: Long,
  val partition: Int,
  val key: String,
  val metadata: MessageMetadata,
  val timeStamp: Long
)

data class StovePublishedMessage(
  val topic: String,
  val value: String,
  val partition: Int,
  val key: String,
  val metadata: MessageMetadata,
  val timeStamp: Long
)

internal fun <K, V : Any> ConsumerRecord<K, V>.toStoveConsumedMessage(): StoveConsumedMessage = StoveConsumedMessage(
  this.topic(),
  this.value().toString(),
  this.offset(),
  this.partition(),
  this.key().toString(),
  this.toMetadata(),
  this.timestamp()
)

internal fun <K, V : Any> ProducerRecord<K, V>.toStovePublishedMessage(): StovePublishedMessage = StovePublishedMessage(
  this.topic(),
  this.value().toString(),
  this.partition(),
  this.key().toString(),
  this.toMetadata(),
  this.timestamp()
)
