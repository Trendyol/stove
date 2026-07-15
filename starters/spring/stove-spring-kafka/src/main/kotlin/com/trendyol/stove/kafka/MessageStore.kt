package com.trendyol.stove.kafka

import com.trendyol.stove.kafka.common.*
import com.trendyol.stove.messaging.Failure
import java.util.UUID

internal class MessageStore {
  internal val core = KafkaMessageStore<DefaultKafkaRecord>()

  fun record(record: StoveMessage.Consumed) {
    core.recordConsumed(record.toRecord())
  }

  fun record(record: StoveMessage.Published) {
    core.recordPublished(record.toRecord())
  }

  fun record(failure: Failure<StoveMessage.Failed>) {
    core.recordFailed(failure.message.actual.toRecord())
  }

  fun consumedRecords(): List<StoveMessage.Consumed> = core.consumedMessages().sources()

  fun producedRecords(): List<StoveMessage.Published> = core.publishedMessages().sources()

  fun failedRecords(): List<StoveMessage.Failed> = core.failedMessages().sources()

  override fun toString(): String = core.toString()

  private fun StoveMessage.toRecord() = DefaultKafkaRecord(
    id = UUID.randomUUID().toString(),
    value = value,
    metadata = metadata,
    partition = partition,
    offset = (this as? StoveMessage.Consumed)?.offset,
    timestamp = timestamp,
    reason = (this as? StoveMessage.Failed)?.reason,
    source = this
  )

  private inline fun <reified T : StoveMessage> Collection<DefaultKafkaRecord>.sources(): List<T> =
    mapNotNull { it.source as? T }
}
