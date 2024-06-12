package com.trendyol.stove.testing.e2e.standalone.kafka.intercepting

import com.trendyol.stove.testing.e2e.standalone.kafka.*
import io.exoquery.pprint

class MessageStore {
  private val consumed = Caching.of<String, ConsumedMessage>()
  private val published = Caching.of<String, PublishedMessage>()
  private val committed = Caching.of<String, CommittedMessage>()
  private val retried = Caching.of<String, ConsumedMessage>()
  private val failedMessages = Caching.of<String, ConsumedMessage>()

  fun record(message: ConsumedMessage) = consumed.put(message.id, message)

  fun record(message: PublishedMessage) = published.put(message.id, message)

  fun record(message: CommittedMessage) = committed.put(message.id, message)

  fun recordRetry(message: ConsumedMessage) = retried.put(message.id, message)

  fun recordFailure(message: ConsumedMessage) = failedMessages.put(message.id, message)

  fun failedMessages(): Collection<ConsumedMessage> = failedMessages.asMap().values

  fun consumedMessages(): Collection<ConsumedMessage> = consumed.asMap().values

  fun publishedMessages(): Collection<PublishedMessage> = published.asMap().values

  fun committedMessages(): Collection<CommittedMessage> = committed.asMap().values

  fun retriedMessages(): Collection<ConsumedMessage> = retried.asMap().values

  fun isCommitted(
    topic: String,
    offsets: List<Long>,
    partition: Int
  ): Boolean = committedMessages()
    .any {
      (offsets.contains(it.offset) || offsets.all { o -> o <= it.offset }) &&
        it.partition == partition &&
        it.topic == topic
    }

  override fun toString(): String = """
    |Consumed: ${pprint(consumedMessages())}
    |Published: ${pprint(publishedMessages())}
    |Committed: ${pprint(committedMessages())}
    |Retried: ${pprint(retriedMessages())}
    |Failed: ${pprint(failedMessages())}
    """.trimIndent().trimMargin()
}
