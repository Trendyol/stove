package com.trendyol.stove.testing.e2e.standalone.kafka.intercepting

import arrow.core.toOption
import com.fasterxml.jackson.databind.ObjectMapper
import com.trendyol.stove.testing.e2e.messaging.*
import com.trendyol.stove.testing.e2e.standalone.kafka.*
import kotlinx.coroutines.*
import org.apache.kafka.clients.admin.Admin
import org.slf4j.Logger
import kotlin.reflect.KClass
import kotlin.time.Duration

internal interface CommonOps {
  val store: MessageStore
  val serde: ObjectMapper
  val adminClient: Admin
  val topicSuffixes: TopicSuffixes
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
    collectionFunc().filter { condition(it) }
  }.fold(
    onFailure = { throw AssertionError("GOT A TIMEOUT: $subject. ${dumpMessages()}") },
    onSuccess = { it }
  )

  suspend fun <T> (suspend () -> Collection<T>).waitUntilCount(
    duration: Duration,
    count: Int
  ): Collection<T> = runCatching {
    val collectionFunc = this
    withTimeout(duration) {
      while (collectionFunc().size < count)
        delay(DELAY_MS)
    }
    collectionFunc()
  }.getOrElse {
    throw AssertionError(
      "GOT A TIMEOUT: While expecting $count items to be retried, " +
        "but was ${this().size}.\n ${dumpMessages()}"
    )
  }

  fun <T : Any> throwIfFailed(
    clazz: KClass<T>,
    selector: (message: ParsedMessage<T>) -> Boolean
  ): Unit = store.failedMessages()
    .filter {
      selector(SuccessfulParsedMessage(readCatching(it.message, clazz).getOrNull().toOption(), it.metadata()))
    }.forEach {
      throw AssertionError("Message was expected to be consumed successfully, but failed: $it \n ${dumpMessages()}")
    }

  fun <T : Any> throwIfRetried(
    clazz: KClass<T>,
    selector: (message: ParsedMessage<T>) -> Boolean
  ): Unit = store.retriedMessages()
    .filter {
      selector(
        SuccessfulParsedMessage(
          readCatching(it.message, clazz).getOrNull().toOption(),
          MessageMetadata(it.topic, it.key, it.headers)
        )
      )
    }.forEach {
      throw AssertionError("Message was expected to be consumed successfully, but was retried: $it \n ${dumpMessages()}")
    }

  fun <T : Any> throwIfSucceeded(
    clazz: KClass<T>,
    selector: (message: ParsedMessage<T>) -> Boolean
  ): Unit = store.consumedMessages()
    .filter {
      selector(
        SuccessfulParsedMessage(
          readCatching(it.message, clazz).getOrNull().toOption(),
          it.metadata()
        )
      ) && store.isCommitted(it.topic, it.offsets(), it.partition)
    }.forEach { throw AssertionError("Message was expected to fail, but was consumed: $it \n ${dumpMessages()}") }

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
