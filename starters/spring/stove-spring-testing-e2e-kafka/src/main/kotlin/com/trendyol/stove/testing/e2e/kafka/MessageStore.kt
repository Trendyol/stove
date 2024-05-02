package com.trendyol.stove.testing.e2e.kafka

import com.trendyol.stove.testing.e2e.messaging.Failure
import io.exoquery.pprint
import java.util.*

internal class MessageStore {
  private val consumed = Caching.of<UUID, StoveMessage.StoveConsumedMessage>()
  private val produced = Caching.of<UUID, StoveMessage.StovePublishedMessage>()
  private val failures = Caching.of<UUID, Failure<StoveMessage>>()

  fun record(record: StoveMessage.StoveConsumedMessage) {
    consumed.put(UUID.randomUUID(), record)
  }

  fun record(record: StoveMessage.StovePublishedMessage) {
    produced.put(UUID.randomUUID(), record)
  }

  fun record(failure: Failure<StoveMessage>) {
    failures.put(UUID.randomUUID(), failure)
  }

  fun consumedRecords(): List<StoveMessage.StoveConsumedMessage> = consumed.asMap().values.toList()

  fun producedRecords(): List<StoveMessage.StovePublishedMessage> = produced.asMap().values.toList()

  fun failedRecords(): List<Failure<StoveMessage>> = failures.asMap().values.toList()

  override fun toString(): String = """
    |Consumed: ${pprint(consumedRecords())}
    |Published: ${pprint(producedRecords())}
    |Failed: ${pprint(failedRecords())}
    """.trimIndent().trimMargin()
}
