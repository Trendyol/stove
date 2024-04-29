package com.trendyol.stove.testing.e2e.standalone.kafka.intercepting

import arrow.core.toOption
import com.trendyol.stove.testing.e2e.messaging.*
import com.trendyol.stove.testing.e2e.standalone.kafka.*
import kotlinx.coroutines.runBlocking
import kotlin.reflect.KClass
import kotlin.time.Duration

internal interface MessageSinkOps : MessageSinkPublishOps, CommonOps {
  suspend fun <T : Any> waitUntilConsumed(
    atLeastIn: Duration,
    clazz: KClass<T>,
    condition: (metadata: ParsedMessage<T>) -> Boolean
  ) {
    store.assertion(MessagingAssertion(clazz, condition))
    val getRecords = { store.consumedMessages().map { it } }
    getRecords.waitUntilConditionMet(atLeastIn, "While CONSUMING ${clazz.java.simpleName}") {
      val outcome = readCatching(it.message, clazz)
      outcome.isSuccess && condition(
        SuccessfulParsedMessage(outcome.getOrNull().toOption(), MessageMetadata(it.topic, it.key, it.headers))
      )
    }

    throwIfFailed(clazz, condition)
  }

  @Suppress("UNCHECKED_CAST")
  suspend fun <T : Any> waitUntilFailed(
    atLeastIn: Duration,
    clazz: KClass<T>,
    condition: (FailedParsedMessage<T>) -> Boolean
  ) {
    store.assertion(MessagingAssertion(clazz, condition as (ParsedMessage<T>) -> Boolean))
    val getRecords = { store.failedMessages<T>().map { it } }
    getRecords.waitUntilConditionMet(atLeastIn, "While RETRYING ${clazz.java.simpleName}") {
      val outcome = readCatching(it.message, clazz)
      outcome.isSuccess && condition(FailedParsedMessage(outcome.getOrNull().toOption(), it.message.metadata, it.reason))
    }

    throwIfFailed(clazz, condition)
  }

  suspend fun <T : Any> waitUntilRetried(
    atLeastIn: Duration,
    times: Int = 1,
    clazz: KClass<T>,
    condition: (message: ParsedMessage<T>) -> Boolean
  ) {
    store.assertion(MessagingAssertion(clazz, condition))
    val getRecords = { store.retriedMessages().map { it } }
    val failedFunc = suspend {
      getRecords.waitUntilConditionMet(atLeastIn, "While RETRYING ${clazz.java.simpleName}") {
        val outcome = readCatching(it.message, clazz)
        outcome.isSuccess && condition(
          SuccessfulParsedMessage(
            outcome.getOrNull().toOption(),
            MessageMetadata(it.topic, it.key, it.headers)
          )
        )
      }
    }

    failedFunc.waitUntilCount(atLeastIn, times)

    throwIfFailed(clazz, condition)
  }

  fun recordConsumed(record: ConsumedMessage): Unit = runBlocking {
    store.record(record)
    logger.info(
      """
         RECEIVED MESSAGE:
         Topic: ${record.topic}
         Record: ${record.message}
         Key: ${record.key}
         Headers: ${record.headers.map { Pair(it.key, it.value) }}
         TestCase: ${record.headers.firstNotNullOf { it.key == "testCase" }}
      """.trimIndent()
    )
  }

  fun recordRetry(record: ConsumedMessage): Unit = runBlocking {
    store.recordRetry(record)
    logger.info(
      """
         RETRIED MESSAGE:
         Topic: ${record.topic}
         Record: ${record.message}
         Key: ${record.key}
         Headers: ${record.headers.map { Pair(it.key, it.value) }}
         TestCase: ${record.headers.firstNotNullOf { it.key == "testCase" }}
      """.trimIndent()
    )
  }

  fun recordCommittedMessage(record: CommittedMessage): Unit = runBlocking {
    store.record(record)
    logger.info(
      """
         |COMMITTED MESSAGE:
         |Topic: ${record.topic}
         |Offset: ${record.offset}
         |Partition: ${record.partition}
      """.trimIndent().trimMargin()
    )
  }

  fun recordError(record: ConsumedMessage): Unit = runBlocking {
    val exception = AssertionError(buildErrorMessage(record))
    store.recordFailure(Failure(ObservedMessage(record.message, MessageMetadata(record.topic, record.message, record.headers)), exception))
    logger.error(
      """
        |CONSUMER GOT AN ERROR:
        |Topic: ${record.topic}
        |Record: ${record.message}
        |Key: ${record.key}
        |Headers: ${record.headers.map { Pair(it.key, it.value) }}
        |TestCase: ${record.headers.firstNotNullOf { it.key == "testCase" }}
        |Exception: $exception
      """.trimIndent().trimMargin()
    )
  }

  private fun buildErrorMessage(record: ConsumedMessage): String =
    """
        |MESSAGE FAILED TO CONSUME:
        |Topic: ${record.topic}
        |Record: ${record.message}
        |Key: ${record.key}
        |Headers: ${record.headers.map { Pair(it.key, it.value) }}
    """.trimIndent().trimMargin()

  override fun dumpMessages(): String = """
        |CONSUMED MESSAGES SO FAR: 
        |${store.consumedMessages().map { it }.joinToString("\n")}
    """.trimIndent().trimMargin()
}
