package com.trendyol.stove.testing.e2e.kafka

import com.trendyol.stove.testing.e2e.messaging.MessageMetadata
import io.exoquery.pprint

sealed interface MessageProperties {
  val topic: String
  val value: ByteArray
  val valueAsString: String
  val metadata: MessageMetadata
  val partition: Int?
  val key: String?
  val timestamp: Long?
}

internal sealed class StoveMessage : MessageProperties {
  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (javaClass != other?.javaClass) return false

    other as Consumed

    if (topic != other.topic) return false
    if (!value.contentEquals(other.value)) return false
    if (metadata != other.metadata) return false
    if (partition != other.partition) return false
    if (key != other.key) return false
    if (timestamp != other.timestamp) return false

    return true
  }

  override fun hashCode(): Int {
    var result = topic.hashCode()
    result = 31 * result + value.contentHashCode()
    result = 31 * result + metadata.hashCode()
    result = 31 * result + (partition ?: 0)
    result = 31 * result + (key?.hashCode() ?: 0)
    result = 31 * result + (timestamp?.hashCode() ?: 0)
    return result
  }

  data class Consumed(
    override val topic: String,
    override val value: ByteArray,
    override val metadata: MessageMetadata,
    override val partition: Int?,
    override val key: String?,
    override val timestamp: Long?,
    val offset: Long?,
    override val valueAsString: String = String(value)
  ) : StoveMessage() {
    override fun hashCode(): Int = super.hashCode() + offset.hashCode()

    override fun equals(other: Any?): Boolean = super.equals(other) && other is Consumed && offset == other.offset

    override fun toString(): String = pprint(this.copy(value = ByteArray(0))).toString()
  }

  data class Published(
    override val topic: String,
    override val value: ByteArray,
    override val metadata: MessageMetadata,
    override val partition: Int?,
    override val key: String?,
    override val timestamp: Long?,
    override val valueAsString: String = String(value)
  ) : StoveMessage() {
    override fun hashCode(): Int = super.hashCode()

    override fun equals(other: Any?): Boolean = super.equals(other)

    override fun toString(): String = pprint(this.copy(value = ByteArray(0))).toString()
  }

  companion object {
    fun consumed(
      topic: String,
      value: ByteArray,
      metadata: MessageMetadata,
      partition: Int? = null,
      key: String? = null,
      timestamp: Long? = null,
      offset: Long? = null
    ): Consumed = Consumed(topic, value, metadata, partition, key, timestamp, offset)

    fun published(
      topic: String,
      value: ByteArray,
      metadata: MessageMetadata,
      partition: Int? = null,
      key: String? = null,
      timestamp: Long? = null
    ): Published = Published(topic, value, metadata, partition, key, timestamp)
  }
}
