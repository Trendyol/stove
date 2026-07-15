package com.trendyol.stove.kafka.common

import com.trendyol.stove.messaging.MessageMetadata

/**
 * Transport-neutral view of a Kafka record observed by Stove.
 *
 * Standalone Kafka and Spring Kafka keep their native records at the transport edge and adapt
 * them to this contract before storing or asserting on them.
 */
interface KafkaRecord {
  val id: String
  val value: ByteArray
  val metadata: MessageMetadata
  val partition: Int?
  val offset: Long?
  val timestamp: Long?
  val reason: Throwable?

  val topic: String
    get() = metadata.topic

  val key: String
    get() = metadata.key

  val headers: Map<String, Any>
    get() = metadata.headers
}

/** Default adapter used by integrations that need to retain their native source record. */
class DefaultKafkaRecord(
  override val id: String,
  override val value: ByteArray,
  override val metadata: MessageMetadata,
  override val partition: Int? = null,
  override val offset: Long? = null,
  override val timestamp: Long? = null,
  override val reason: Throwable? = null,
  val source: Any? = null
) : KafkaRecord {
  override fun toString(): String = source?.toString() ?: "KafkaRecord(" +
    "id=$id, topic=$topic, key=$key, partition=$partition, offset=$offset, " +
    "headers=$headers, value=${String(value)}, reason=${reason?.message})"
}
