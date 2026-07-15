package com.trendyol.stove.kafka.intercepting

import com.trendyol.stove.kafka.*
import com.trendyol.stove.messaging.MessageMetadata
import com.trendyol.stove.messaging.kafka.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.*

sealed interface StoveMessageEvent {
  data class Consumed(val message: ConsumedMessage) : StoveMessageEvent

  data class Published(val message: PublishedMessage) : StoveMessageEvent

  data class Committed(val message: CommittedMessage) : StoveMessageEvent

  data class Retried(val message: ConsumedMessage) : StoveMessageEvent

  data class Failed(val message: ConsumedMessage) : StoveMessageEvent

  data class Acknowledged(val message: AcknowledgedMessage) : StoveMessageEvent
}

/** Standalone Kafka facade over the transport-neutral message store. */
class MessageStore {
  internal val core = KafkaMessageStore<DefaultKafkaRecord>()

  private val mutableEvents = MutableSharedFlow<StoveMessageEvent>(
    extraBufferCapacity = EVENT_BUFFER_CAPACITY,
    onBufferOverflow = BufferOverflow.DROP_OLDEST
  )

  val version: StateFlow<Long> = core.version
  val events: SharedFlow<StoveMessageEvent> = mutableEvents.asSharedFlow()

  fun consumedRecords(): Flow<ConsumedMessage> = core.consumedRecords().mapNotNull { it.source as? ConsumedMessage }

  fun publishedRecords(): Flow<PublishedMessage> = core.publishedRecords().mapNotNull { it.source as? PublishedMessage }

  fun committedRecords(): Flow<CommittedMessage> = core.committedRecords().mapNotNull { it.source as? CommittedMessage }

  fun retriedRecords(): Flow<ConsumedMessage> = core.retriedRecords().mapNotNull { it.source as? ConsumedMessage }

  fun failedRecords(): Flow<ConsumedMessage> = core.failedRecords().mapNotNull { it.source as? ConsumedMessage }

  internal fun record(message: ConsumedMessage) {
    core.recordConsumed(message.toRecord())
    mutableEvents.tryEmit(StoveMessageEvent.Consumed(message))
  }

  internal fun record(message: PublishedMessage) {
    core.recordPublished(message.toRecord())
    mutableEvents.tryEmit(StoveMessageEvent.Published(message))
  }

  internal fun record(message: CommittedMessage) {
    core.recordCommitted(message.toRecord())
    mutableEvents.tryEmit(StoveMessageEvent.Committed(message))
  }

  internal fun record(message: AcknowledgedMessage) {
    core.recordAcknowledged(message.toRecord())
    mutableEvents.tryEmit(StoveMessageEvent.Acknowledged(message))
  }

  internal fun recordRetry(message: ConsumedMessage) {
    core.recordRetried(message.toRecord())
    mutableEvents.tryEmit(StoveMessageEvent.Retried(message))
  }

  internal fun recordFailure(message: ConsumedMessage) {
    core.recordFailed(message.toRecord())
    mutableEvents.tryEmit(StoveMessageEvent.Failed(message))
  }

  fun failedMessages(): Collection<ConsumedMessage> = core.failedMessages().sources()

  fun consumedMessages(): Collection<ConsumedMessage> = core.consumedMessages().sources()

  fun publishedMessages(): Collection<PublishedMessage> = core.publishedMessages().sources()

  fun committedMessages(): Collection<CommittedMessage> = core.committedMessages().sources()

  fun retriedMessages(): Collection<ConsumedMessage> = core.retriedMessages().sources()

  internal fun isCommitted(
    topic: String,
    offset: Long,
    partition: Int
  ): Boolean = core.isCommitted(topic, partition, offset)

  fun dump(testId: String?): String = core.dump(testId)

  override fun toString(): String = core.toString()

  private inline fun <reified T : Any> Collection<DefaultKafkaRecord>.sources(): List<T> =
    mapNotNull { it.source as? T }

  private fun ConsumedMessage.toRecord() = DefaultKafkaRecord(
    id = id,
    value = message.toByteArray(),
    metadata = metadata(),
    partition = partition,
    offset = offset,
    source = this
  )

  private fun PublishedMessage.toRecord() = DefaultKafkaRecord(
    id = id,
    value = message.toByteArray(),
    metadata = metadata(),
    source = this
  )

  private fun CommittedMessage.toRecord() = DefaultKafkaRecord(
    id = id,
    value = byteArrayOf(),
    // The stable Wire contract has no headers; KafkaMessageStore scopes commits via consumed records.
    metadata = MessageMetadata(topic, "", emptyMap()),
    partition = partition,
    offset = offset,
    source = this
  )

  private fun AcknowledgedMessage.toRecord() = DefaultKafkaRecord(
    id = id,
    value = byteArrayOf(),
    // The stable Wire contract has no headers; KafkaMessageStore scopes acks via published topics.
    metadata = MessageMetadata(topic, "", emptyMap()),
    partition = partition,
    offset = offset,
    source = this
  )

  companion object {
    private const val EVENT_BUFFER_CAPACITY = 4096
  }
}
