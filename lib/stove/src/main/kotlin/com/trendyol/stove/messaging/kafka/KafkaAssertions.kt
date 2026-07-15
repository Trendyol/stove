package com.trendyol.stove.messaging.kafka

import arrow.core.some
import com.trendyol.stove.messaging.*
import com.trendyol.stove.serialization.StoveSerde
import com.trendyol.stove.tracing.TraceContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.*
import kotlin.reflect.KClass
import kotlin.time.Duration

/** Shared assertion engine for standalone and Spring Kafka observation transports. */
class KafkaAssertions<R : KafkaRecord>(
  private val store: KafkaMessageStore<R>,
  private val serde: StoveSerde<Any, ByteArray>,
  private val isErrorTopic: (String) -> Boolean = { false },
  private val requireConsumedCommit: Boolean = false,
  private val failIfConsumedWhileWaitingForFailure: Boolean = false
) {
  private val logger: Logger = LoggerFactory.getLogger(javaClass)

  suspend fun <T : Any> waitUntilConsumed(
    atLeastIn: Duration,
    clazz: KClass<T>,
    condition: (ParsedMessage<T>) -> Boolean
  ) {
    val testId = TraceContext.current()?.testId
    val matching = awaitRecords(
      within = atLeastIn,
      subject = "While expecting consuming of ${clazz.java.simpleName}",
      testId = testId,
      query = {
        store.consumedMessages().scoped(testId).map(ConsumptionOutcome<R>::Consumed) +
          store.failedMessages().scoped(testId).map(ConsumptionOutcome<R>::Failed) +
          store.retriedMessages().scoped(testId).map(ConsumptionOutcome<R>::Retried)
      }
    ) { outcome ->
      matches(outcome.record, clazz, condition) &&
        (outcome !is ConsumptionOutcome.Consumed || !requireConsumedCommit || store.isCommitted(outcome.record))
    }

    matching.filterIsInstance<ConsumptionOutcome.Failed<R>>().firstOrNull()?.let {
      throw AssertionError(
        "Message was expected to be consumed successfully, but failed: ${it.record} \n ${dumpMessages(testId)}"
      )
    }
    matching.filterIsInstance<ConsumptionOutcome.Retried<R>>().firstOrNull()?.let {
      throw AssertionError(
        "Message was expected to be consumed successfully, but was retried: ${it.record} \n ${dumpMessages(testId)}"
      )
    }
  }

  suspend fun <T : Any> waitUntilPublished(
    atLeastIn: Duration,
    clazz: KClass<T>,
    condition: (ParsedMessage<T>) -> Boolean
  ) {
    val testId = TraceContext.current()?.testId
    awaitRecords(
      within = atLeastIn,
      subject = "While expecting publishing of ${clazz.java.simpleName}",
      testId = testId,
      query = { store.publishedMessages().scoped(testId) }
    ) { matches(it, clazz, condition) }
  }

  suspend fun <T : Any> waitUntilFailed(
    atLeastIn: Duration,
    clazz: KClass<T>,
    condition: (ParsedMessage<T>) -> Boolean
  ) {
    val testId = TraceContext.current()?.testId
    val matching = awaitRecords(
      within = atLeastIn,
      subject = "While expecting failure of ${clazz.java.simpleName}",
      testId = testId,
      query = {
        store.failedMessages().scoped(testId) +
          store
            .publishedMessages()
            .filter { isErrorTopic(it.topic) }
            .scoped(testId)
      }
    ) { record -> matches(record, clazz, condition) }

    if (failIfConsumedWhileWaitingForFailure) {
      store.consumedMessages().scoped(testId).firstOrNull { consumed ->
        matching.any { failed -> consumed.sameMessageAs(failed) }
      }?.let {
        throw AssertionError("Message was expected to fail, but was consumed successfully: $it \n ${dumpMessages(testId)}")
      }
    }
  }

  suspend fun <T : Any> waitUntilRetried(
    atLeastIn: Duration,
    times: Int = 1,
    clazz: KClass<T>,
    condition: (ParsedMessage<T>) -> Boolean
  ) {
    require(times > 0) { "times must be greater than zero" }

    val testId = TraceContext.current()?.testId
    awaitRecords(
      within = atLeastIn,
      subject = "While expecting retrying of ${clazz.java.simpleName}",
      testId = testId,
      count = times,
      query = { store.retriedMessages().scoped(testId) }
    ) { matches(it, clazz, condition) }
  }

  private suspend fun <T> awaitRecords(
    within: Duration,
    subject: String,
    testId: String?,
    count: Int = 1,
    query: () -> Collection<T>,
    predicate: (T) -> Boolean
  ): Collection<T> {
    require(count > 0) { "count must be greater than zero" }

    var matching = emptyList<T>()
    val matched = withTimeoutOrNull(within) {
      store.version.first {
        matching = query().filter(predicate)
        matching.size >= count
      }
      matching
    }
    return matched ?: throw AssertionError(
      "GOT A TIMEOUT: $subject. Expected at least $count matching message(s) within $within, " +
        "but found ${matching.size}. ${dumpMessages(testId)}"
    )
  }

  private fun <T : Any> matches(
    record: R,
    clazz: KClass<T>,
    condition: (ParsedMessage<T>) -> Boolean
  ): Boolean = deserializeCatching(record.value, clazz)
    .map { value ->
      val parsed = record.reason?.let { FailedParsedMessage(value.some(), record.metadata, it) }
        ?: SuccessfulParsedMessage(value.some(), record.metadata)
      condition(parsed)
    }.getOrDefault(false)

  private fun KafkaRecord.sameMessageAs(other: KafkaRecord): Boolean =
    topic == other.topic &&
      key == other.key &&
      partition == other.partition &&
      (offset == null || other.offset == null || offset == other.offset) &&
      value.contentEquals(other.value)

  private fun <T : Any> deserializeCatching(value: ByteArray, clazz: KClass<T>): Result<T> =
    runCatching { serde.deserialize(value, clazz.java) }
      .onFailure { exception -> logger.debug("Failed to deserialize Kafka message: ${String(value)}", exception) }

  private fun Collection<R>.scoped(testId: String?): List<R> = filter { it.headers.belongsToTest(testId) }

  private fun dumpMessages(testId: String?): String = "Messages so far:\n${store.dump(testId)}"

  private sealed interface ConsumptionOutcome<out R : KafkaRecord> {
    val record: R

    data class Consumed<R : KafkaRecord>(override val record: R) : ConsumptionOutcome<R>

    data class Failed<R : KafkaRecord>(override val record: R) : ConsumptionOutcome<R>

    data class Retried<R : KafkaRecord>(override val record: R) : ConsumptionOutcome<R>
  }
}
