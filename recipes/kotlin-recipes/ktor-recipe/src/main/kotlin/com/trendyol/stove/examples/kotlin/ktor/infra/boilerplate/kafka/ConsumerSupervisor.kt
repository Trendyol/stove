package com.trendyol.stove.examples.kotlin.ktor.infra.boilerplate.kafka

import io.github.nomisRev.kafka.receiver.KafkaReceiver
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.apache.kafka.clients.consumer.ConsumerRecord
import java.time.Duration

abstract class ConsumerSupervisor<K, V>(
  private val kafkaReceiver: KafkaReceiver<K, V>,
  private val maxConcurrency: Int
) {
  private val logger = KotlinLogging.logger("ConsumerSupervisor[${javaClass.simpleName}]")
  private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
  private val recordChannel = Channel<ConsumerRecord<K, V>>(maxConcurrency)

  abstract val topics: List<String>

  fun start() {
    scope.launch {
      logger.info { "Receiving records from topics: $topics" }
      subscribe()
    }
    logger.info { "Consuming records with concurrency: $maxConcurrency" }
  }

  @Suppress("TooGenericExceptionCaught")
  private suspend fun subscribe() {
    kafkaReceiver.withConsumer { consumer ->
      consumer.subscribe(topics)
      while (scope.isActive) {
        val records = consumer.poll(Duration.ofMillis(100))
        records.forEach { record ->
          logger.debug { "Received record: $record" }
          try {
            consume(record)
            consumer.commitAsync()
          } catch (e: Exception) {
            handleError(e, record)
          }
        }
      }
    }
  }

  abstract suspend fun consume(record: ConsumerRecord<K, V>)

  protected open fun handleError(e: Exception, record: ConsumerRecord<K, V>) {
    logger.error(e) { "Error while processing record: $record" }
  }

  fun cancel() {
    logger.info { "Cancelling consumer supervisor" }
    scope.cancel()
  }

  /**
   * Offers the record to the channel. If the channel is full, it suspends until a space becomes available.
   */
  @Suppress("TooGenericExceptionCaught")
  private fun consume() = repeat(maxConcurrency) {
    scope.launch {
      for (record in recordChannel) {
        try {
          consume(record)
        } catch (e: Exception) {
          handleError(e, record)
        }
      }
    }
  }
}
