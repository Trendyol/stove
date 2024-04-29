package com.trendyol.stove.testing.e2e.standalone.kafka.intercepting

import arrow.core.toOption
import com.trendyol.stove.testing.e2e.messaging.*
import com.trendyol.stove.testing.e2e.standalone.kafka.PublishedMessage
import kotlinx.coroutines.runBlocking
import kotlin.reflect.KClass
import kotlin.time.Duration

internal interface MessageSinkPublishOps : CommonOps {
  suspend fun <T : Any> waitUntilPublished(
    atLeastIn: Duration,
    clazz: KClass<T>,
    condition: (message: ParsedMessage<T>) -> Boolean
  ) {
    store.assertion(MessagingAssertion(clazz, condition))
    val getRecords = { store.publishedMessages().map { it } }
    getRecords.waitUntilConditionMet(atLeastIn, "While PUBLISHING ${clazz.java.simpleName}") {
      val outcome = readCatching(it.message, clazz)
      outcome.isSuccess && condition(SuccessfulParsedMessage(outcome.getOrNull().toOption(), MessageMetadata(it.topic, it.key, it.headers)))
    }

    throwIfFailed(clazz, condition)
  }

  fun recordPublishedMessage(record: PublishedMessage): Unit = runBlocking {
    store.record(record)
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
}
