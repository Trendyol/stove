package com.trendyol.stove.testing.e2e.standalone.kafka.intercepting

import com.trendyol.stove.testing.e2e.messaging.*
import com.trendyol.stove.testing.e2e.standalone.kafka.*
import java.util.*

@Suppress("UNCHECKED_CAST")
class MessageStore {
  private val consumed = Caching.of<String, ConsumedMessage>()
  private val published = Caching.of<String, PublishedMessage>()
  private val committed = Caching.of<String, CommittedMessage>()
  private val retried = Caching.of<String, ConsumedMessage>()
  private val assertions = Caching.of<String, MessagingAssertion<*>>()
  private val exceptions = Caching.of<UUID, Failure<*>>()

  fun record(message: ConsumedMessage) = consumed.put(message.id, message)

  fun record(message: PublishedMessage) = published.put(message.id, message)

  fun record(message: CommittedMessage) = committed.put(message.id, message)

  fun recordRetry(message: ConsumedMessage) = retried.put(message.id, message)

  fun <T> recordFailure(failure: Failure<T>) = exceptions.put(UUID.randomUUID(), failure)

  fun <T> failedMessages(): Collection<Failure<T>> = exceptions.asMap().values.map { it as Failure<T> }

  fun consumedMessages(): Collection<ConsumedMessage> = consumed.asMap().values

  fun publishedMessages(): Collection<PublishedMessage> = published.asMap().values

  fun committedMessages(): Collection<CommittedMessage> = committed.asMap().values

  fun retriedMessages(): Collection<ConsumedMessage> = retried.asMap().values

  fun assertion(assertion: MessagingAssertion<*>) = assertions.put(UUID.randomUUID().toString(), assertion)

  fun assertions(): Collection<MessagingAssertion<*>> = assertions.asMap().values

  fun clear() {
    consumed.invalidateAll()
    published.invalidateAll()
    committed.invalidateAll()
    retried.invalidateAll()
    assertions.invalidateAll()
    exceptions.invalidateAll()
  }
}
