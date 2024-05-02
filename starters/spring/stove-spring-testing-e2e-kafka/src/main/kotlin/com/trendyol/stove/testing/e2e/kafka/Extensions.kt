package com.trendyol.stove.testing.e2e.kafka

import arrow.core.Option
import com.fasterxml.jackson.databind.ObjectMapper
import com.trendyol.stove.testing.e2e.messaging.MessageMetadata
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
  objectMapper: ObjectMapper
): StoveMessage.StoveConsumedMessage =
  StoveMessage.StoveConsumedMessage(
    this.topic(),
    serializeIfNotString(this.value(), objectMapper),
    this.toMetadata(),
    this.offset(),
    this.partition(),
    this.key().toString(),
    this.timestamp()
  )

internal fun <K, V> ProducerRecord<K, V>.toStoveMessage(
  objectMapper: ObjectMapper
): StoveMessage.StovePublishedMessage = StoveMessage.StovePublishedMessage(
  this.topic(),
  serializeIfNotString(this.value(), objectMapper),
  this.toMetadata(),
  this.partition(),
  this.key().toString(),
  this.timestamp()
)

private fun <V> serializeIfNotString(
  value: V,
  objectMapper: ObjectMapper
): String = if (value is String) value else objectMapper.writeValueAsString(value)

internal fun (MutableMap<String, String>).addTestCase(testCase: Option<String>): MutableMap<String, String> =
  if (this.containsKey("testCase")) this else testCase.map { this["testCase"] = it }.let { this }
