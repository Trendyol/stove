package com.trendyol.stove.testing.e2e.kafka

import com.trendyol.stove.testing.e2e.messaging.Failure
import io.exoquery.pprint
import java.util.*

internal class MessageStore {
  private val consumed = Caching.of<UUID, StoveMessage.Consumed>()
  private val produced = Caching.of<UUID, StoveMessage.Published>()
  private val failures = Caching.of<UUID, Failure<StoveMessage>>()

  fun record(record: StoveMessage.Consumed) {
    consumed.put(UUID.randomUUID(), record)
  }

  fun record(record: StoveMessage.Published) {
    produced.put(UUID.randomUUID(), record)
  }

  fun record(failure: Failure<StoveMessage>) {
    failures.put(UUID.randomUUID(), failure)
  }

  fun consumedRecords(): List<StoveMessage.Consumed> = consumed.asMap().values.toList()

  fun producedRecords(): List<StoveMessage.Published> = produced.asMap().values.toList()

  fun failedRecords(): List<Failure<StoveMessage>> = failures.asMap().values.toList()

  override fun toString(): String = """
    |Consumed: ${pprint(consumedRecords().map { it.copy(value = ByteArray(0)) })}
    |Published: ${pprint(producedRecords().map { it.copy(value = ByteArray(0)) })}
    |Failed: ${pprint(failedRecords())}
    """.trimIndent().trimMargin()
}
