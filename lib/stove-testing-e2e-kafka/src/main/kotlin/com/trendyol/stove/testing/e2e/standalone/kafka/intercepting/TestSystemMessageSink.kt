package com.trendyol.stove.testing.e2e.standalone.kafka.intercepting

import CommittedMessage
import ConsumedMessage
import PublishedMessage
import com.fasterxml.jackson.databind.ObjectMapper
import org.apache.kafka.clients.admin.Admin
import org.slf4j.*
import java.util.*
import java.util.concurrent.*

class TestSystemMessageSink(
  override val adminClient: Admin,
  override val serde: ObjectMapper,
  private val options: InterceptionOptions
) : ConsumingOps, CommonOps {
  override val logger: Logger = LoggerFactory.getLogger(javaClass)
  override val consumedRecords: ConcurrentMap<UUID, ConsumedMessage> = ConcurrentHashMap()
  override val publishedMessages: ConcurrentMap<UUID, PublishedMessage> = ConcurrentHashMap()
  override val committedMessages: ConcurrentMap<UUID, CommittedMessage> = ConcurrentHashMap()
  override val exceptions: ConcurrentMap<UUID, Failure> = ConcurrentHashMap()
  override val assertions: ConcurrentMap<UUID, KafkaAssertion<*>> = ConcurrentHashMap()

  fun onMessageConsumed(record: ConsumedMessage): Unit = when {
    options.isErrorTopic(record.topic) -> recordError(record)
    else -> recordConsumedMessage(record)
  }

  fun onMessagePublished(record: PublishedMessage): Unit = recordPublishedMessage(record)

  fun onMessageCommitted(record: CommittedMessage): Unit = recordCommittedMessage(record)
}
