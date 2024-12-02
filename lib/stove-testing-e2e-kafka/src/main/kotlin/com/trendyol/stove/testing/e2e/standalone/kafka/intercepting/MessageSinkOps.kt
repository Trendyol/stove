package com.trendyol.stove.testing.e2e.standalone.kafka.intercepting

import arrow.core.toOption
import com.trendyol.stove.testing.e2e.messaging.*
import com.trendyol.stove.testing.e2e.standalone.kafka.*
import kotlinx.coroutines.runBlocking
import kotlin.reflect.KClass
import kotlin.time.Duration

internal interface MessageSinkOps : MessageSinkPublishOps, CommonOps {
  fun recordConsumed(record: ConsumedMessage): Unit = runBlocking {
    store.record(record)
    logger.info(
      "Recorded Consumed Message: {}, testCase: {}",
      record,
      record.headers.firstNotNullOf { it.key == "testCase" }
    )
  }

  fun recordRetry(record: ConsumedMessage): Unit = runBlocking {
    store.recordRetry(record)
    logger.info(
      "Recorded Retried Message: {}, testCase: {}",
      record,
      record.headers.firstNotNullOf { it.key == "testCase" }
    )
  }

  fun recordCommittedMessage(record: CommittedMessage): Unit = runBlocking {
    store.record(record)
    logger.info("Recorded Committed Message:{}", record)
  }

  fun recordAcknowledgedMessage(record: AcknowledgedMessage): Unit = runBlocking {
    store.record(record)
    logger.info("Recorded Acknowledged Message:{}", record)
  }

  fun recordError(record: ConsumedMessage): Unit = runBlocking {
    store.recordFailure(record)
    logger.info("Recorded Failed Message: {}", record)
  }

  suspend fun <T : Any> waitUntilConsumed(
    atLeastIn: Duration,
    clazz: KClass<T>,
    condition: (metadata: ParsedMessage<T>) -> Boolean
  ) {
    val getRecords = { store.consumedMessages() }
    getRecords.waitUntilConditionMet(atLeastIn, "While expecting consuming of ${clazz.java.simpleName}") {
      val outcome = readCatching(it.message.toByteArray(), clazz)
      outcome.isSuccess && condition(
        SuccessfulParsedMessage(
          outcome.getOrNull().toOption(),
          it.metadata()
        )
      ) && store.isCommitted(it.topic, it.offset, it.partition)
    }

    throwIfFailed(clazz, condition)
    throwIfRetried(clazz, condition)
  }

  suspend fun <T : Any> waitUntilFailed(
    atLeastIn: Duration,
    clazz: KClass<T>,
    condition: (ParsedMessage<T>) -> Boolean
  ) {
    class FailedMessage(val message: ByteArray, val metadata: MessageMetadata)

    val getRecords = {
      store.failedMessages().map { FailedMessage(it.message.toByteArray(), it.metadata()) } +
        store.publishedMessages()
          .filter { topicSuffixes.isErrorTopic(it.topic) }
          .map { FailedMessage(it.message.toByteArray(), it.metadata()) }
    }
    getRecords.waitUntilConditionMet(atLeastIn, "While expecting Failure of ${clazz.java.simpleName}") {
      val outcome = readCatching(it.message, clazz)
      outcome.isSuccess && condition(SuccessfulParsedMessage(outcome.getOrNull().toOption(), it.metadata))
    }
  }

  suspend fun <T : Any> waitUntilRetried(
    atLeastIn: Duration,
    times: Int = 1,
    clazz: KClass<T>,
    condition: (message: ParsedMessage<T>) -> Boolean
  ) {
    val getRecords = { store.retriedMessages() }
    val failedFunc = suspend {
      getRecords.waitUntilConditionMet(atLeastIn, "While expecting Retrying of ${clazz.java.simpleName}") {
        val outcome = readCatching(it.message.toByteArray(), clazz)
        outcome.isSuccess && condition(
          SuccessfulParsedMessage(
            outcome.getOrNull().toOption(),
            MessageMetadata(it.topic, it.key, it.headers)
          )
        )
      }
    }

    failedFunc.waitUntilCount(atLeastIn, times)
  }

  override fun dumpMessages(): String = "Sink so far:\n$store"
}
