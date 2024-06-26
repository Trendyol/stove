package com.trendyol.stove.testing.e2e.standalone.kafka

import com.trendyol.stove.testing.e2e.messaging.MessageMetadata
import org.apache.kafka.clients.consumer.*
import org.apache.kafka.clients.producer.*
import org.apache.kafka.common.TopicPartition
import java.util.*
import kotlin.coroutines.*

fun <K, V> Map<K, V>.toProperties(): Properties =
  Properties().apply {
    this@toProperties.forEach { (k, v) -> this[k] = v }
  }

fun ConsumedMessage.metadata(): MessageMetadata = MessageMetadata(
  topic = topic,
  key = key,
  headers = headers
)

fun PublishedMessage.metadata(): MessageMetadata = MessageMetadata(
  topic = topic,
  key = key,
  headers = headers
)

fun ConsumedMessage.offsets(): List<Long> = offsets.map { it.offset } + offset

suspend inline fun <reified K : Any, reified V : Any> KafkaProducer<K, V>.dispatch(
  record: ProducerRecord<K, V>
): RecordMetadata = suspendCoroutine { continuation ->
  val callback = Callback { metadata, exception ->
    if (exception != null) {
      continuation.resumeWithException(exception)
    } else {
      continuation.resume(metadata)
    }
  }
  send(record, callback)
}

fun <K, V> ConsumerRecord<K, V>.offsets(
  metadata: ((record: ConsumerRecord<K, V>) -> String)? = null
): Map<TopicPartition, OffsetAndMetadata> = buildMap {
  val key = TopicPartition(topic(), partition())
  val value = metadata?.let {
    OffsetAndMetadata(offset() + 1, leaderEpoch(), metadata(this@offsets))
  } ?: OffsetAndMetadata(offset() + 1)
  put(key, value)
}
