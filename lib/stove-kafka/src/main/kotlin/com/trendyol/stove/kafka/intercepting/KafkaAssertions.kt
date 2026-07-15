package com.trendyol.stove.kafka.intercepting

import arrow.core.some
import com.trendyol.stove.kafka.*
import com.trendyol.stove.messaging.*
import com.trendyol.stove.serialization.StoveSerde
import com.trendyol.stove.tracing.TraceContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.*
import kotlin.reflect.KClass
import kotlin.time.Duration

/**
 * Signal-driven assertion engine over a [MessageStore].
 *
 * Owns waiting, deserialization matching, test-id scoping, and failure dumps. It has no
 * transport knowledge: any integration that records into a [MessageStore] through
 * [KafkaRecorder] gets these assertion semantics unchanged.
 */
class KafkaAssertions(
  private val store: MessageStore,
  private val serde: StoveSerde<Any, ByteArray>,
  private val topicSuffixes: TopicSuffixes
) {
  private val logger: Logger = LoggerFactory.getLogger(javaClass)

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

  /**
   * Suspends until at least [count] records returned by [query] match [predicate], returning
   * the matching records. Throws an [AssertionError] carrying the test-scoped store dump when
   * [within] elapses first.
   *
   * Waiting is signal-driven: [MessageStore.version] is a StateFlow, so the condition is
   * evaluated immediately and after observed store changes. Exceptions thrown by [predicate]
   * propagate as-is; only an elapsed timeout becomes an assertion failure.
   */
  private suspend fun <T> awaitRecords(
    within: Duration,
    subject: String,
    testId: String?,
    count: Int = 1,
    query: () -> Collection<T>,
    predicate: (T) -> Boolean
  ): Collection<T> {
    val matching = { query().filter(predicate) }
    val matched = withTimeoutOrNull(within) {
      store.version.first { matching().size >= count }
      matching()
    }
    return matched ?: throw AssertionError(
      "GOT A TIMEOUT: $subject. Expected at least $count matching message(s) within $within, " +
        "but found ${matching().size}. ${dumpMessages(testId)}"
    )
  }

  /**
   * True when the payload deserializes as [clazz] and the parsed message satisfies [condition].
   */
  private fun <T : Any> matches(
    payload: ByteArray,
    metadata: MessageMetadata,
    clazz: KClass<T>,
    condition: (ParsedMessage<T>) -> Boolean
  ): Boolean = deserializeCatching(payload, clazz)
    .map { condition(SuccessfulParsedMessage(it.some(), metadata)) }
    .getOrDefault(false)

  private fun <T : Any> throwIfFailed(
    clazz: KClass<T>,
    testId: String?,
    selector: (message: ParsedMessage<T>) -> Boolean
  ): Unit = store
    .failedMessages()
    .filter { it.headers.belongsToTest(testId) }
    .filter { matches(it.message.toByteArray(), it.metadata(), clazz, selector) }
    .forEach {
      throw AssertionError("Message was expected to be consumed successfully, but failed: $it \n ${dumpMessages(testId)}")
    }

  private fun <T : Any> throwIfRetried(
    clazz: KClass<T>,
    testId: String?,
    selector: (message: ParsedMessage<T>) -> Boolean
  ): Unit = store
    .retriedMessages()
    .filter { it.headers.belongsToTest(testId) }
    .filter { matches(it.message.toByteArray(), it.metadata(), clazz, selector) }
    .forEach {
      throw AssertionError("Message was expected to be consumed successfully, but was retried: $it \n ${dumpMessages(testId)}")
    }

  private fun <T : Any> deserializeCatching(
    value: ByteArray,
    clazz: KClass<T>
  ): Result<T> = runCatching { serde.deserialize(value, clazz.java) }
    .onFailure { exception -> logger.debug("Failed to deserialize message: ${String(value)}", exception) }

  private fun dumpMessages(testId: String?): String = "Sink so far:\n${store.dump(testId)}"
}
