package com.trendyol.stove.testing.e2e.standalone.kafka.intercepting

import com.trendyol.stove.testing.e2e.serialization.StoveSerde
import com.trendyol.stove.testing.e2e.standalone.kafka.*
import org.apache.kafka.clients.admin.Admin
import org.slf4j.*

class TestSystemMessageSink(
  override val adminClient: Admin,
  override val serde: StoveSerde<Any, ByteArray>,
  override val topicSuffixes: TopicSuffixes
) : MessageSinkOps,
  CommonOps {
  override val logger: Logger = LoggerFactory.getLogger(javaClass)
  override val store: MessageStore = MessageStore()

  fun onMessageConsumed(record: ConsumedMessage): Unit = when {
    topicSuffixes.isErrorTopic(record.topic) -> recordError(record)
    topicSuffixes.isRetryTopic(record.topic) -> recordRetry(record)
    else -> recordConsumed(record)
  }

  fun onMessagePublished(record: PublishedMessage): Unit = recordPublishedMessage(record)

  fun onMessageCommitted(record: CommittedMessage): Unit = recordCommittedMessage(record)

  fun onMessageAcknowledged(record: AcknowledgedMessage): Unit = recordAcknowledgedMessage(record)
}
