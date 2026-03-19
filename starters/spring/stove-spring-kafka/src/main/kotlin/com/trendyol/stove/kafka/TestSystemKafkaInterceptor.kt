package com.trendyol.stove.kafka

import arrow.core.toOption
import com.trendyol.stove.messaging.*
import com.trendyol.stove.serialization.StoveSerde
import kotlinx.coroutines.*
import org.apache.kafka.clients.consumer.*
import org.apache.kafka.clients.producer.*
import org.slf4j.*
import org.springframework.kafka.listener.*
import org.springframework.kafka.support.ProducerListener
import kotlin.reflect.KClass
import kotlin.time.Duration

/**
 * This is the main actor between your Kafka Spring Boot application and the test system.
 * It is responsible for intercepting the messages that are produced and consumed by the application.
 * It also provides a way to wait until a message is consumed or produced.
 *
 * @param serde The serializer/deserializer that will be used to serialize/deserialize the messages.
 * It is important to use the same serde that is used in the application.
 * For example, if the application uses Avro, then you should use Avro serde here.
 * Target of the serialization is ByteArray, so the serde should be able to serialize the message to ByteArray.
 */
class TestSystemKafkaInterceptor<K : Any, V : Any>(
  private val serde: StoveSerde<Any, ByteArray>
) : CompositeRecordInterceptor<K, V>(),
  ProducerListener<K, V> {
  private val logger: Logger = LoggerFactory.getLogger(javaClass)
  private val store = MessageStore()

  /**
   * Get access to the message store for reporting purposes.
   */
  internal fun getStore(): MessageStore = store

  override fun onSuccess(
    record: ProducerRecord<K, V>,
    recordMetadata: RecordMetadata
  ) {
    val message = record.toStoveMessage(serde)
    store.record(message)
    logger.info("Successfully produced:\n{}", message)
  }

  override fun onError(
    record: ProducerRecord<K, V>,
    recordMetadata: RecordMetadata?,
    exception: Exception
  ) {
    val underlyingReason = extractCause(exception)
    val message = record.toFailedStoveMessage(serde, underlyingReason)
    store.record(Failure(ObservedMessage(message, record.toMetadata()), underlyingReason))
    logger.error("Error while producing:\n{}", message, exception)
  }

  override fun success(record: ConsumerRecord<K, V>, consumer: Consumer<K, V>) {
    val message = record.toStoveMessage(serde)
    store.record(message)
    logger.info("Successfully consumed:\n{}", message)
  }

  override fun failure(
    record: ConsumerRecord<K, V>,
    exception: Exception,
    consumer: Consumer<K, V>
  ) {
    val underlyingReason = extractCause(exception)
    val message = record.toFailedStoveMessage(serde, underlyingReason)
    store.record(Failure(ObservedMessage(message, record.toMetadata()), underlyingReason))
    logger.error("Error while consuming:\n{}", message, exception)
  }

  internal suspend fun <T : Any> waitUntilConsumed(
    atLeastIn: Duration,
    clazz: KClass<T>,
    condition: (metadata: ParsedMessage<T>) -> Boolean
  ) {
    val getRecords = { store.consumedRecords() }
    getRecords.waitUntilConditionMet(atLeastIn, "While expecting the consume of '${clazz.java.simpleName}'") {
      val outcome = deserializeCatching(it.value, clazz)
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
      val outcome = deserializeCatching(it.value, clazz)
      outcome.isSuccess && condition(FailedParsedMessage(outcome.getOrNull().toOption(), it.metadata, it.reason))
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
      val outcome = deserializeCatching(it.value, clazz)
      outcome.isSuccess && condition(SuccessfulParsedMessage(outcome.getOrNull().toOption(), it.metadata))
    }
  }

  private fun extractCause(
    listenerException: Exception
  ): Exception = when (listenerException) {
    is ListenerExecutionFailedException -> {
      listenerException.cause
        ?: AssertionError("No cause found: Listener was not able to capture the cause")
    }

    else -> {
      listenerException
    }
  } as Exception

  private fun <T : Any> deserializeCatching(
    value: ByteArray,
    clazz: KClass<T>
  ): Result<T> = runCatching { serde.deserialize(value, clazz.java) }
    .onFailure { logger.debug("[Stove#deserializeCatching] Error while deserializing: '{}'", String(value), it) }

  private fun <T : Any> throwIfFailed(
    clazz: KClass<T>,
    selector: (message: ParsedMessage<T>) -> Boolean
  ) = store
    .failedRecords()
    .filter {
      selector(
        FailedParsedMessage(
          deserializeCatching(it.value, clazz).getOrNull().toOption(),
          MessageMetadata(it.metadata.topic, it.metadata.key, it.metadata.headers),
          it.reason
        )
      )
    }.forEach {
      throw AssertionError(
        "Message was expected to be consumed successfully, but failed: $it \n ${dumpMessages()}"
      )
    }

  private fun <T : Any> throwIfSucceeded(
    clazz: KClass<T>,
    selector: (ParsedMessage<T>) -> Boolean
  ): Unit = store
    .consumedRecords()
    .filter { record ->
      selector(
        FailedParsedMessage(
          deserializeCatching(record.value, clazz).getOrNull().toOption(),
          record.metadata,
          getExceptionFor(clazz, selector)
        )
      )
    }.forEach { throw AssertionError("Expected to fail but succeeded: $it") }

  private fun <T : Any> getExceptionFor(
    clazz: KClass<T>,
    selector: (message: FailedParsedMessage<T>) -> Boolean
  ): Throwable = store
    .failedRecords()
    .first {
      selector(FailedParsedMessage(deserializeCatching(it.value, clazz).getOrNull().toOption(), it.metadata, it.reason))
    }.reason

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
