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
    val getRecords = { store.publishedMessages().map { it } }
    getRecords.waitUntilConditionMet(atLeastIn, "While expecting Publishing of ${clazz.java.simpleName}") {
      val outcome = deserializeCatching(it.message.toByteArray(), clazz)
      outcome.isSuccess && condition(SuccessfulParsedMessage(outcome.getOrNull().toOption(), MessageMetadata(it.topic, it.key, it.headers)))
    }
  }

  fun recordPublishedMessage(record: PublishedMessage): Unit = runBlocking {
    store.record(record)
    logger.info(
      "Recorded Published Message: {}, testCase: {}",
      record,
      record.headers.firstNotNullOf { it.key == "testCase" }
    )
  }
}
