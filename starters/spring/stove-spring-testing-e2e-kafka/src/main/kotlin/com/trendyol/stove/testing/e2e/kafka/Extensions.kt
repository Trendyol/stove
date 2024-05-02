package com.trendyol.stove.testing.e2e.kafka

import arrow.core.Option
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
  val metadata: MessageMetadata,
  val offset: Long?,
  val partition: Int?,
  val key: String?,
  val timeStamp: Long?
)

data class StovePublishedMessage(
  val topic: String,
  val value: String,
  val metadata: MessageMetadata,
  val partition: Int?,
  val key: String?,
  val timeStamp: Long?
)

internal fun <K, V : Any> ConsumerRecord<K, V>.toStoveConsumedMessage(): StoveConsumedMessage = StoveConsumedMessage(
  this.topic(),
  this.value().toString(),
  this.toMetadata(),
  this.offset(),
  this.partition(),
  this.key().toString(),
  this.timestamp()
)

internal fun <K, V : Any> ProducerRecord<K, V>.toStovePublishedMessage(): StovePublishedMessage = StovePublishedMessage(
  this.topic(),
  this.value().toString(),
  this.toMetadata(),
  this.partition(),
  this.key().toString(),
  this.timestamp()
)

internal fun (MutableMap<String, String>).addTestCase(testCase: Option<String>): MutableMap<String, String> =
  if (this.containsKey("testCase")) this else testCase.map { this["testCase"] = it }.let { this }
