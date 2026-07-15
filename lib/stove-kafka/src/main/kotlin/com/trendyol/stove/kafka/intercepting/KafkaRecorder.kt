package com.trendyol.stove.kafka.intercepting

import com.trendyol.stove.kafka.*
import org.slf4j.*

/**
 * Write surface of the Kafka observer: records what the bridge reports into the [MessageStore],
 * classifying consumed records into consumed/retried/failed by the application's topic suffixes.
 *
 * Transports (the gRPC observer server here, other interceptors elsewhere) depend on this class
 * only — never on the assertion engine ([KafkaAssertions]) — and the store's raw record methods
 * stay internal behind it.
 */
class KafkaRecorder(
  private val store: MessageStore,
  private val topicSuffixes: TopicSuffixes
) {
  private val logger: Logger = LoggerFactory.getLogger(javaClass)

  fun onMessageConsumed(record: ConsumedMessage) {
    when {
      topicSuffixes.isErrorTopic(record.topic) -> {
        store.recordFailure(record)
        logger.info("Recorded Failed Message: {}", record)
      }

      topicSuffixes.isRetryTopic(record.topic) -> {
        store.recordRetry(record)
        logger.info("Recorded Retried Message: {}, testCase: {}", record, record.headers["testCase"])
      }

      else -> {
        store.record(record)
        logger.info("Recorded Consumed Message: {}, testCase: {}", record, record.headers["testCase"])
      }
    }
  }

  fun onMessagePublished(record: PublishedMessage) {
    store.record(record)
    logger.info("Recorded Published Message: {}, testCase: {}", record, record.headers["testCase"])
  }

  fun onMessageCommitted(record: CommittedMessage) {
    store.record(record)
    logger.info("Recorded Committed Message:{}", record)
  }

  fun onMessageAcknowledged(record: AcknowledgedMessage) {
    store.record(record)
    logger.info("Recorded Acknowledged Message:{}", record)
  }
}
