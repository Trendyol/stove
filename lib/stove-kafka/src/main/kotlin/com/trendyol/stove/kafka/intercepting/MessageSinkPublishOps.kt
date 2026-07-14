package com.trendyol.stove.kafka.intercepting

import com.trendyol.stove.kafka.*
import com.trendyol.stove.messaging.ParsedMessage
import com.trendyol.stove.tracing.TraceContext
import kotlin.reflect.KClass
import kotlin.time.Duration

internal interface MessageSinkPublishOps : CommonOps {
  suspend fun <T : Any> waitUntilPublished(
    atLeastIn: Duration,
    clazz: KClass<T>,
    condition: (message: ParsedMessage<T>) -> Boolean
  ) {
    val testId = TraceContext.current()?.testId
    awaitRecords(
      within = atLeastIn,
      subject = "While expecting Publishing of ${clazz.java.simpleName}",
      testId = testId,
      query = { store.publishedMessages().filter { it.headers.belongsToTest(testId) } }
    ) { matches(it.message.toByteArray(), it.metadata(), clazz, condition) }
  }

  fun recordPublishedMessage(record: PublishedMessage) {
    store.record(record)
    logger.info("Recorded Published Message: {}, testCase: {}", record, record.headers["testCase"])
  }
}
