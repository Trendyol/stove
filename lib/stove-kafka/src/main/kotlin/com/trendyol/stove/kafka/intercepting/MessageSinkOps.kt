package com.trendyol.stove.kafka.intercepting

import com.trendyol.stove.kafka.*
import com.trendyol.stove.messaging.ParsedMessage
import com.trendyol.stove.tracing.TraceContext
import kotlin.reflect.KClass
import kotlin.time.Duration

internal interface MessageSinkOps :
  MessageSinkPublishOps,
  CommonOps {
  fun recordConsumed(record: ConsumedMessage) {
    store.record(record)
    logger.info("Recorded Consumed Message: {}, testCase: {}", record, record.headers["testCase"])
  }

  fun recordRetry(record: ConsumedMessage) {
    store.recordRetry(record)
    logger.info("Recorded Retried Message: {}, testCase: {}", record, record.headers["testCase"])
  }

  fun recordCommittedMessage(record: CommittedMessage) {
    store.record(record)
    logger.info("Recorded Committed Message:{}", record)
  }

  fun recordAcknowledgedMessage(record: AcknowledgedMessage) {
    store.record(record)
    logger.info("Recorded Acknowledged Message:{}", record)
  }

  fun recordError(record: ConsumedMessage) {
    store.recordFailure(record)
    logger.info("Recorded Failed Message: {}", record)
  }

  suspend fun <T : Any> waitUntilConsumed(
    atLeastIn: Duration,
    clazz: KClass<T>,
    condition: (metadata: ParsedMessage<T>) -> Boolean
  ) {
    val testId = TraceContext.current()?.testId
    awaitRecords(
      within = atLeastIn,
      subject = "While expecting consuming of ${clazz.java.simpleName}",
      testId = testId,
      query = { store.consumedMessages().filter { it.headers.belongsToTest(testId) } }
    ) {
      matches(it.message.toByteArray(), it.metadata(), clazz, condition) &&
        store.isCommitted(it.topic, it.offset, it.partition)
    }

    throwIfFailed(clazz, testId, condition)
    throwIfRetried(clazz, testId, condition)
  }

  suspend fun <T : Any> waitUntilFailed(
    atLeastIn: Duration,
    clazz: KClass<T>,
    condition: (ParsedMessage<T>) -> Boolean
  ) {
    val testId = TraceContext.current()?.testId
    awaitRecords(
      within = atLeastIn,
      subject = "While expecting Failure of ${clazz.java.simpleName}",
      testId = testId,
      query = {
        store
          .failedMessages()
          .filter { it.headers.belongsToTest(testId) }
          .map { it.message.toByteArray() to it.metadata() } +
          store
            .publishedMessages()
            .filter { topicSuffixes.isErrorTopic(it.topic) && it.headers.belongsToTest(testId) }
            .map { it.message.toByteArray() to it.metadata() }
      }
    ) { (payload, metadata) -> matches(payload, metadata, clazz, condition) }
  }

  suspend fun <T : Any> waitUntilRetried(
    atLeastIn: Duration,
    times: Int = 1,
    clazz: KClass<T>,
    condition: (message: ParsedMessage<T>) -> Boolean
  ) {
    val testId = TraceContext.current()?.testId
    awaitRecords(
      within = atLeastIn,
      subject = "While expecting Retrying of ${clazz.java.simpleName}",
      testId = testId,
      count = times,
      query = { store.retriedMessages().filter { it.headers.belongsToTest(testId) } }
    ) { matches(it.message.toByteArray(), it.metadata(), clazz, condition) }
  }

  override fun dumpMessages(testId: String?): String = "Sink so far:\n${store.dump(testId)}"
}
