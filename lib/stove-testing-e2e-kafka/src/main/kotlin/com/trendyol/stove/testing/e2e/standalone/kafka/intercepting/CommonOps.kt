package com.trendyol.stove.testing.e2e.standalone.kafka.intercepting

import arrow.core.toOption
import com.fasterxml.jackson.databind.ObjectMapper
import com.trendyol.stove.testing.e2e.messaging.FailedParsedMessage
import kotlinx.coroutines.*
import org.apache.kafka.clients.admin.Admin
import org.slf4j.Logger
import kotlin.reflect.KClass
import kotlin.time.Duration

internal interface CommonOps {
  val store: MessageStore
  val serde: ObjectMapper
  val adminClient: Admin
  val logger: Logger

  companion object {
    const val DELAY_MS = 50L
  }

  suspend fun <T> (() -> Collection<T>).waitUntilConditionMet(
    duration: Duration,
    subject: String,
    condition: (T) -> Boolean
  ): Collection<T> = runCatching {
    val collectionFunc = this
    withTimeout(duration) {
      while (!collectionFunc().any { condition(it) })
        delay(DELAY_MS)
    }
    return collectionFunc().filter { condition(it) }
  }.recoverCatching {
    when (it) {
      is TimeoutCancellationException -> throw AssertionError("GOT A TIMEOUT: $subject. ${dumpMessages()}")
      is ConcurrentModificationException -> Result.success(waitUntilConditionMet(duration, subject, condition))
      else -> throw it
    }.getOrThrow()
  }.getOrThrow()

  suspend fun <T> (suspend () -> Collection<T>).waitUntilCount(
    duration: Duration,
    count: Int
  ): Collection<T> = runCatching {
    val collectionFunc = this
    withTimeout(duration) {
      while (collectionFunc().size < count)
        delay(DELAY_MS)
    }
    return collectionFunc()
  }.recoverCatching {
    when (it) {
      is TimeoutCancellationException -> throw AssertionError("GOT A TIMEOUT: $count items. ${dumpMessages()}")
      is ConcurrentModificationException -> Result.success(waitUntilCount(duration, count))
      else -> throw it
    }.getOrThrow()
  }.getOrThrow()

  fun <T : Any> throwIfFailed(
    clazz: KClass<T>,
    selector: (message: FailedParsedMessage<T>) -> Boolean
  ): Unit = store.failedMessages<T>()
    .filter {
      selector(FailedParsedMessage(readCatching(it.message.toString(), clazz).getOrNull().toOption(), it.message.metadata, it.reason))
    }
    .forEach { throw it.reason }

  fun <T : Any> readCatching(
    json: Any,
    clazz: KClass<T>
  ): Result<T> = runCatching {
    when (json) {
      is String -> serde.readValue(json, clazz.java)
      else -> serde.convertValue(json, clazz.java)
    }
  }

  fun dumpMessages(): String
}
