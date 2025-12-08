package com.trendyol.stove.testing.e2e.kafka

import arrow.core.Option
import com.trendyol.stove.testing.e2e.messaging.MessageMetadata
import com.trendyol.stove.testing.e2e.serialization.StoveSerde
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.producer.ProducerRecord

internal fun <K, V> ProducerRecord<K, V>.toMetadata(): MessageMetadata = MessageMetadata(
  this.topic(),
  this.key().toString(),
  this.headers().associate { h -> Pair(h.key(), String(h.value())) }
)

internal fun <K, V> ConsumerRecord<K, V>.toMetadata(): MessageMetadata = MessageMetadata(
  this.topic(),
  this.key().toString(),
  this.headers().associate { h -> Pair(h.key(), String(h.value())) }
)

internal fun <K, V> ConsumerRecord<K, V>.toStoveMessage(
  serde: StoveSerde<Any, ByteArray>
): StoveMessage.Consumed = StoveMessage.consumed(
  this.topic(),
  serializeIfNotYet(this.value(), serde),
  this.toMetadata(),
  this.partition(),
  this.key()?.toString() ?: "",
  this.timestamp(),
  this.offset()
)

internal fun <K, V> ConsumerRecord<K, V>.toFailedStoveMessage(
  serde: StoveSerde<Any, ByteArray>,
  exception: Exception
): StoveMessage.Failed = StoveMessage.failed(
  this.topic(),
  serializeIfNotYet(this.value(), serde),
  this.toMetadata(),
  exception,
  this.partition(),
  this.key()?.toString() ?: "",
  this.timestamp()
)

internal fun <K, V> ProducerRecord<K, V>.toStoveMessage(
  serde: StoveSerde<Any, ByteArray>
): StoveMessage.Published = StoveMessage.published(
  this.topic(),
  serializeIfNotYet(this.value(), serde),
  this.toMetadata(),
  this.partition(),
  this.key()?.toString() ?: "",
  this.timestamp()
)

internal fun <K, V> ProducerRecord<K, V>.toFailedStoveMessage(
  serde: StoveSerde<Any, ByteArray>,
  exception: Exception
): StoveMessage.Failed = StoveMessage.failed(
  this.topic(),
  serializeIfNotYet(this.value(), serde),
  this.toMetadata(),
  exception,
  this.partition(),
  this.key()?.toString() ?: "",
  this.timestamp()
)

private fun <V> serializeIfNotYet(
  value: V,
  serde: StoveSerde<Any, ByteArray>
): ByteArray = when (value) {
  is ByteArray -> value
  else -> serde.serialize(value as Any)
}

internal fun (MutableMap<String, String>).addTestCase(testCase: Option<String>): MutableMap<String, String> =
  if (this.containsKey("testCase")) this else testCase.map { this["testCase"] = it }.let { this }
