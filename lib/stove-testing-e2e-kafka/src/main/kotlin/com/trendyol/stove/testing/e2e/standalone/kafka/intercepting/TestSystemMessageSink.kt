package com.trendyol.stove.testing.e2e.standalone.kafka.intercepting

import com.fasterxml.jackson.databind.ObjectMapper
import com.trendyol.stove.testing.e2e.standalone.kafka.*
import org.apache.kafka.clients.admin.Admin
import org.slf4j.*

class TestSystemMessageSink(
  override val adminClient: Admin,
  override val serde: ObjectMapper,
  private val options: TopicSuffixes
) : MessageSinkOps, CommonOps {
  override val logger: Logger = LoggerFactory.getLogger(javaClass)
  override val store: MessageStore = MessageStore()

  fun onMessageConsumed(record: ConsumedMessage): Unit = when {
    options.isErrorTopic(record.topic) -> recordError(record)
    options.isRetryTopic(record.topic) -> recordRetry(record)
    else -> recordConsumed(record)
  }

  fun onMessagePublished(record: PublishedMessage): Unit = recordPublishedMessage(record)

  fun onMessageCommitted(record: CommittedMessage): Unit = recordCommittedMessage(record)
}
