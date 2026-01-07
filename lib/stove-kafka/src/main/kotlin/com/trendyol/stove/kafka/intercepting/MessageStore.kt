package com.trendyol.stove.kafka.intercepting

import com.trendyol.stove.kafka.*
import io.exoquery.pprint

class MessageStore {
  private val consumed = Caching.of<String, ConsumedMessage>()
  private val published = Caching.of<String, PublishedMessage>()
  private val committed = Caching.of<String, CommittedMessage>()
  private val retried = Caching.of<String, ConsumedMessage>()
  private val failedMessages = Caching.of<String, ConsumedMessage>()
  private val acknowledged = Caching.of<String, AcknowledgedMessage>()

  internal fun record(message: ConsumedMessage) = consumed.put(message.id, message)

  internal fun record(message: PublishedMessage) = published.put(message.id, message)

  internal fun record(message: CommittedMessage) = committed.put(message.id, message)

  internal fun record(message: AcknowledgedMessage) = acknowledged.put(message.id, message)

  internal fun recordRetry(message: ConsumedMessage) = retried.put(message.id, message)

  internal fun recordFailure(message: ConsumedMessage) = failedMessages.put(message.id, message)

  fun failedMessages(): Collection<ConsumedMessage> = failedMessages.asMap().values

  fun consumedMessages(): Collection<ConsumedMessage> = consumed.asMap().values

  fun publishedMessages(): Collection<PublishedMessage> = published.asMap().values

  fun committedMessages(): Collection<CommittedMessage> = committed.asMap().values

  fun retriedMessages(): Collection<ConsumedMessage> = retried.asMap().values

  internal fun isCommitted(
    topic: String,
    offset: Long,
    partition: Int
  ): Boolean = committedMessages()
    .filter { it.topic == topic && it.partition == partition }
    .any { committed -> committed.offset >= offset + 1 }

  override fun toString(): String = """
    |Consumed: ${pprint(consumedMessages())}
    |Published: ${pprint(publishedMessages())}
    |Committed: ${pprint(committedMessages())}
    |Retried: ${pprint(retriedMessages())}
    |Failed: ${pprint(failedMessages())}
    """.trimIndent().trimMargin()
}
