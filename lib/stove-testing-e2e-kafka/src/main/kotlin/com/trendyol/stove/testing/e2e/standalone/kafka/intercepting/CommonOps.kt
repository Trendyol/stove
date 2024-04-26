package com.trendyol.stove.testing.e2e.standalone.kafka.intercepting

import arrow.core.*
import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.*
import org.apache.kafka.clients.admin.Admin
import java.util.*
import java.util.concurrent.ConcurrentMap
import kotlin.reflect.KClass
import kotlin.time.Duration

data class KafkaAssertion<T : Any>(
  val clazz: KClass<T>,
  val condition: (Option<T>) -> Boolean
)

internal interface CommonOps : RecordsAssertions {
  val exceptions: ConcurrentMap<UUID, Failure>
  val serde: ObjectMapper
  val adminClient: Admin

  companion object {
    const val DELAY_MS = 50L
  }

  suspend fun <T> (() -> Collection<T>).waitUntilConditionMet(
    duration: Duration,
    subject: String,
    condition: (T) -> Boolean
  ): Collection<T> = runCatching {
    val collectionFunc = this
    withTimeout(duration) { while (!collectionFunc().any { condition(it) }) delay(DELAY_MS) }
    return collectionFunc().filter { condition(it) }
  }.recoverCatching {
    when (it) {
      is TimeoutCancellationException -> throw AssertionError("GOT A TIMEOUT: $subject. ${dumpMessages()}")
      is ConcurrentModificationException ->
        Result.success(waitUntilConditionMet(duration, subject, condition))

      else -> throw it
    }.getOrThrow()
  }.getOrThrow()

  fun <T : Any> throwIfFailed(
    clazz: KClass<T>,
    selector: (Option<T>) -> Boolean
  ): Unit = exceptions
    .filter { selector(readCatching(it.value.message.toString(), clazz).getOrNull().toOption()) }
    .forEach { throw it.value.reason }

  fun <T : Any> readCatching(
    json: Any,
    clazz: KClass<T>
  ): Result<T> = runCatching {
    when (json) {
      is String -> {
        val converted = serde.readValue(json, clazz.java)
        converted
      }

      else -> {
        val converted = serde.convertValue(json, clazz.java)
        converted
      }
    }
  }

  fun reset(): Unit = exceptions.clear()

  fun dumpMessages(): String
}
