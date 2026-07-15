package com.trendyol.stove.kafka.intercepting

import arrow.core.some
import com.trendyol.stove.kafka.*
import com.trendyol.stove.messaging.*
import com.trendyol.stove.serialization.StoveSerde
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import org.apache.kafka.clients.admin.Admin
import org.slf4j.Logger
import kotlin.reflect.KClass
import kotlin.time.Duration

internal interface CommonOps {
  val store: MessageStore
  val serde: StoveSerde<Any, ByteArray>
  val adminClient: Admin
  val topicSuffixes: TopicSuffixes
  val logger: Logger

  /**
   * Suspends until at least [count] records returned by [query] match [predicate], returning
   * the matching records. Throws an [AssertionError] carrying the test-scoped store dump when
   * [within] elapses first.
   *
   * Waiting is signal-driven: [MessageStore.version] is a StateFlow, so the condition is
   * evaluated immediately and after observed store changes. Exceptions thrown by [predicate]
   * propagate as-is; only an elapsed timeout becomes an assertion failure.
   */
  suspend fun <T> awaitRecords(
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
  fun <T : Any> matches(
    payload: ByteArray,
    metadata: MessageMetadata,
    clazz: KClass<T>,
    condition: (ParsedMessage<T>) -> Boolean
  ): Boolean = deserializeCatching(payload, clazz)
    .map { condition(SuccessfulParsedMessage(it.some(), metadata)) }
    .getOrDefault(false)

  fun <T : Any> throwIfFailed(
    clazz: KClass<T>,
    testId: String? = null,
    selector: (message: ParsedMessage<T>) -> Boolean
  ): Unit = store
    .failedMessages()
    .filter { it.headers.belongsToTest(testId) }
    .filter { matches(it.message.toByteArray(), it.metadata(), clazz, selector) }
    .forEach {
      throw AssertionError("Message was expected to be consumed successfully, but failed: $it \n ${dumpMessages(testId)}")
    }

  fun <T : Any> throwIfRetried(
    clazz: KClass<T>,
    testId: String? = null,
    selector: (message: ParsedMessage<T>) -> Boolean
  ): Unit = store
    .retriedMessages()
    .filter { it.headers.belongsToTest(testId) }
    .filter { matches(it.message.toByteArray(), it.metadata(), clazz, selector) }
    .forEach {
      throw AssertionError("Message was expected to be consumed successfully, but was retried: $it \n ${dumpMessages(testId)}")
    }

  fun <T : Any> deserializeCatching(
    value: ByteArray,
    clazz: KClass<T>
  ): Result<T> = runCatching { serde.deserialize(value, clazz.java) }
    .onFailure { exception -> logger.debug("Failed to deserialize message: ${String(value)}", exception) }

  fun dumpMessages(testId: String?): String
}
