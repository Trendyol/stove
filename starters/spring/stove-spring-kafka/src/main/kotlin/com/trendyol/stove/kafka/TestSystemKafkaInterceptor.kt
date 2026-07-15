package com.trendyol.stove.kafka

import com.trendyol.stove.messaging.*
import com.trendyol.stove.messaging.kafka.KafkaAssertions
import com.trendyol.stove.serialization.StoveSerde
import org.apache.kafka.clients.consumer.*
import org.apache.kafka.clients.producer.*
import org.slf4j.*
import org.springframework.kafka.listener.*
import org.springframework.kafka.support.ProducerListener
import kotlin.reflect.KClass
import kotlin.time.Duration

/** Spring Kafka transport adapter for Stove's shared record store and assertion engine. */
class TestSystemKafkaInterceptor<K : Any, V : Any>(
  private val serde: StoveSerde<Any, ByteArray>
) : CompositeRecordInterceptor<K, V>(),
  ProducerListener<K, V> {
  private val logger: Logger = LoggerFactory.getLogger(javaClass)
  private val store = MessageStore()
  private val assertions = KafkaAssertions(
    store = store.core,
    serde = serde,
    failIfConsumedWhileWaitingForFailure = true
  )

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
    condition: (ParsedMessage<T>) -> Boolean
  ): Unit = assertions.waitUntilConsumed(atLeastIn, clazz, condition)

  internal suspend fun <T : Any> waitUntilFailed(
    atLeastIn: Duration,
    clazz: KClass<T>,
    condition: (ParsedMessage<T>) -> Boolean
  ): Unit = assertions.waitUntilFailed(atLeastIn, clazz, condition)

  internal suspend fun <T : Any> waitUntilPublished(
    atLeastIn: Duration,
    clazz: KClass<T>,
    condition: (ParsedMessage<T>) -> Boolean
  ): Unit = assertions.waitUntilPublished(atLeastIn, clazz, condition)

  private fun extractCause(listenerException: Exception): Throwable = when (listenerException) {
    is ListenerExecutionFailedException ->
      listenerException.cause
        ?: IllegalStateException("No cause found: Listener was not able to capture the cause", listenerException)

    else -> listenerException
  }
}
