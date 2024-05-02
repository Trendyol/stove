package com.trendyol.stove.testing.e2e.kafka

import com.trendyol.stove.testing.e2e.messaging.Failure
import io.exoquery.pprint
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.producer.ProducerRecord
import java.util.*

class MessageStore {
  private val consumed = Caching.of<UUID, ConsumerRecord<String, String>>()
  private val produced = Caching.of<UUID, ProducerRecord<String, Any>>()
  private val failures = Caching.of<UUID, Failure<Any>>()

  fun record(record: ConsumerRecord<String, String>) {
    consumed.put(UUID.randomUUID(), record)
  }

  fun record(record: ProducerRecord<String, Any>) {
    produced.put(UUID.randomUUID(), record)
  }

  fun record(failure: Failure<Any>) {
    failures.put(UUID.randomUUID(), failure)
  }

  fun consumedRecords(): List<ConsumerRecord<String, String>> = consumed.asMap().values.toList()

  fun producedRecords(): List<ProducerRecord<String, Any>> = produced.asMap().values.toList()

  fun failedRecords(): List<Failure<Any>> = failures.asMap().values.toList()

  override fun toString(): String = """
    |Consumed: ${pprint(consumedRecords())}
    |Published: ${pprint(producedRecords())}
    |Failed: ${pprint(failedRecords())}
    """.trimIndent().trimMargin()
}
