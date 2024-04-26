package com.trendyol.stove.testing.e2e.standalone.kafka.intercepting

import CommittedMessage
import ConsumedMessage
import PublishedMessage
import arrow.core.*
import kotlinx.coroutines.runBlocking
import org.slf4j.Logger
import java.util.*
import java.util.concurrent.ConcurrentMap
import kotlin.reflect.KClass
import kotlin.time.Duration

internal interface ConsumingOps : CommonOps {
  val logger: Logger
  val consumedRecords: ConcurrentMap<UUID, ConsumedMessage>
  val publishedMessages: ConcurrentMap<UUID, PublishedMessage>
  val committedMessages: ConcurrentMap<UUID, CommittedMessage>

  suspend fun <T : Any> waitUntilConsumed(
    atLeastIn: Duration,
    clazz: KClass<T>,
    condition: (Option<T>) -> Boolean
  ) {
    assertions.putIfAbsent(UUID.randomUUID(), KafkaAssertion(clazz, condition))
    val getRecords = { consumedRecords.map { it.value } }
    getRecords.waitUntilConditionMet(atLeastIn, "While CONSUMING ${clazz.java.simpleName}") {
      val outcome = readCatching(it.message, clazz)
      outcome.isSuccess && condition(outcome.getOrNull().toOption())
    }

    throwIfFailed(clazz, condition)
  }

  fun recordConsumedMessage(record: ConsumedMessage): Unit = runBlocking {
    consumedRecords.putIfAbsent(UUID.randomUUID(), record)
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

  fun recordPublishedMessage(record: PublishedMessage): Unit = runBlocking {
    publishedMessages.putIfAbsent(UUID.randomUUID(), record)
    logger.info(
      """
         PUBLISHED MESSAGE:
         Topic: ${record.topic}
         Record: ${record.message}
         Key: ${record.key}
         Headers: ${record.headers.map { Pair(it.key, it.value) }}
         TestCase: ${record.headers.firstNotNullOf { it.key == "testCase" }}
      """.trimIndent()
    )
  }

  fun recordCommittedMessage(record: CommittedMessage): Unit = runBlocking {
    committedMessages.putIfAbsent(UUID.randomUUID(), record)
    logger.info(
      """
         COMMITTED MESSAGE:
         Topic: ${record.topic}
         Offset: ${record.offset}
         Partition: ${record.partition}
      """.trimIndent()
    )
  }

  fun recordError(record: ConsumedMessage): Unit =
    runBlocking {
      val exception = AssertionError(buildErrorMessage(record))
      exceptions.putIfAbsent(UUID.randomUUID(), Failure(record.topic, record.message, exception))
      logger.error(
        """
                CONSUMER GOT AN ERROR:
                Topic: ${record.topic}
                Record: ${record.message}
                Key: ${record.key}
                Headers: ${record.headers.map { Pair(it.key, it.value) }}
                TestCase: ${record.headers.firstNotNullOf { it.key == "testCase" }}
                Exception: $exception
        """.trimIndent()
      )
    }

  private fun buildErrorMessage(record: ConsumedMessage): String =
    """
        MESSAGE FAILED TO CONSUME:
        Topic: ${record.topic}
        Record: ${record.message}
        Key: ${record.key}
        Headers: ${record.headers.map { Pair(it.key, it.value) }}
    """.trimIndent()

  override fun dumpMessages(): String = """
        CONSUMED MESSAGES SO FAR: 
        ${consumedRecords.map { it.value }.joinToString("\n")}
    """.trimIndent()
}
