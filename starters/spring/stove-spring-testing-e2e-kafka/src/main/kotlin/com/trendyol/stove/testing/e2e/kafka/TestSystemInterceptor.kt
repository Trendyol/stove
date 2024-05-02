package com.trendyol.stove.testing.e2e.kafka

import arrow.core.toOption
import com.fasterxml.jackson.databind.ObjectMapper
import com.trendyol.stove.testing.e2e.messaging.*
import io.exoquery.pprint
import kotlinx.coroutines.*
import org.apache.kafka.clients.consumer.*
import org.apache.kafka.clients.producer.*
import org.slf4j.*
import org.springframework.kafka.listener.*
import org.springframework.kafka.support.ProducerListener
import kotlin.reflect.KClass
import kotlin.time.Duration

class TestSystemKafkaInterceptor<K, V>(
  private val objectMapper: ObjectMapper
) : CompositeRecordInterceptor<K, V>(), ProducerListener<K, V> {
  private val logger: Logger = LoggerFactory.getLogger(javaClass)
  private val store = MessageStore()

  override fun onSuccess(
    record: ProducerRecord<K, V>,
    recordMetadata: RecordMetadata
  ) {
    store.record(record.toStoveMessage(objectMapper))
    logger.info("Successfully produced:\n{}", pprint(record.toStoveMessage(objectMapper)))
  }

  override fun onError(
    record: ProducerRecord<K, V>,
    recordMetadata: RecordMetadata?,
    exception: Exception
  ) {
    store.record(Failure(ObservedMessage(record.toStoveMessage(objectMapper), record.toMetadata()), extractCause(exception)))
    logger.error("Error while producing:\n{}", pprint(record.toStoveMessage(objectMapper)), exception)
  }

  override fun success(record: ConsumerRecord<K, V>, consumer: Consumer<K, V>) {
    store.record(record.toStoveMessage(objectMapper))
    logger.info("Successfully consumed:\n{}", pprint(record.toStoveMessage(objectMapper)))
  }

  override fun failure(
    record: ConsumerRecord<K, V>,
    exception: Exception,
    consumer: Consumer<K, V>
  ) {
    store.record(Failure(ObservedMessage(record.toStoveMessage(objectMapper), record.toMetadata()), extractCause(exception)))
    logger.error("Error while consuming:\n{}", pprint(record.toStoveMessage(objectMapper)), exception)
  }

  internal suspend fun <T : Any> waitUntilConsumed(
    atLeastIn: Duration,
    clazz: KClass<T>,
    condition: (metadata: ParsedMessage<T>) -> Boolean
  ) {
    val getRecords = { store.consumedRecords() }
    getRecords.waitUntilConditionMet(atLeastIn, "While expecting the consume of '${clazz.java.simpleName}'") {
      val outcome = readCatching(it.value, clazz)
      outcome.isSuccess && condition(SuccessfulParsedMessage(outcome.getOrNull().toOption(), it.metadata))
    }

    throwIfFailed(clazz, condition)
  }

  internal suspend fun <T : Any> waitUntilFailed(
    atLeastIn: Duration,
    clazz: KClass<T>,
    condition: (metadata: ParsedMessage<T>) -> Boolean
  ) {
    val getRecords = { store.failedRecords() }
    getRecords.waitUntilConditionMet(atLeastIn, "While expecting the failure of '${clazz.java.simpleName}'") {
      val outcome = readCatching(it.message.actual.value, clazz)
      outcome.isSuccess && condition(FailedParsedMessage(outcome.getOrNull().toOption(), it.message.metadata, it.reason))
    }

    throwIfSucceeded(clazz, condition)
  }

  internal suspend fun <T : Any> waitUntilPublished(
    atLeastIn: Duration,
    clazz: KClass<T>,
    condition: (message: ParsedMessage<T>) -> Boolean
  ) {
    val getRecords = { store.producedRecords() }
    getRecords.waitUntilConditionMet(atLeastIn, "While expecting the publish of '${clazz.java.simpleName}'") {
      val outcome = readCatching(it.value, clazz)
      outcome.isSuccess && condition(SuccessfulParsedMessage(outcome.getOrNull().toOption(), it.metadata))
    }

    throwIfFailed(clazz, condition)
  }

  private fun extractCause(
    listenerException: Throwable
  ): Throwable = when (listenerException) {
    is ListenerExecutionFailedException ->
      listenerException.cause ?: AssertionError("No cause found: Listener was not able to capture the cause")

    else -> listenerException
  }

  private fun <T : Any> readCatching(
    json: String,
    clazz: KClass<T>
  ): Result<T> = runCatching {
    objectMapper.readValue(json, clazz.java)
  }

  private fun <T : Any> throwIfFailed(
    clazz: KClass<T>,
    selector: (message: ParsedMessage<T>) -> Boolean
  ) = store.failedRecords()
    .filter {
      selector(
        FailedParsedMessage(
          readCatching(it.message.actual.value, clazz).getOrNull().toOption(),
          MessageMetadata(it.message.metadata.topic, it.message.metadata.key, it.message.metadata.headers),
          it.reason
        )
      )
    }
    .forEach { throw it.reason }

  private fun <T : Any> throwIfSucceeded(
    clazz: KClass<T>,
    selector: (ParsedMessage<T>) -> Boolean
  ): Unit = store.consumedRecords()
    .filter { record ->
      selector(
        FailedParsedMessage(
          readCatching(record.value, clazz).getOrNull().toOption(),
          record.metadata,
          getExceptionFor(clazz, selector)
        )
      )
    }
    .forEach { throw AssertionError("Expected to fail but succeeded: $it") }

  private fun <T : Any> getExceptionFor(
    clazz: KClass<T>,
    selector: (message: FailedParsedMessage<T>) -> Boolean
  ): Throwable = store.failedRecords()
    .first {
      selector(
        FailedParsedMessage(
          readCatching(it.message.actual.value, clazz).getOrNull().toOption(),
          it.message.metadata,
          it.reason
        )
      )
    }
    .reason

  private suspend fun <T : Any> (() -> Collection<T>).waitUntilConditionMet(
    duration: Duration,
    subject: String,
    delayMs: Long = 50L,
    condition: (T) -> Boolean
  ): Collection<T> = runCatching {
    val collectionFunc = this
    withTimeout(duration) { while (!collectionFunc().any { condition(it) }) delay(delayMs) }
    collectionFunc().filter { condition(it) }
  }.fold(
    onSuccess = { it },
    onFailure = { throw AssertionError("GOT A TIMEOUT: $subject. ${dumpMessages()}") }
  )

  private fun dumpMessages(): String = "Messages so far:\n$store"
}
