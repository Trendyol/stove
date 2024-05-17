package com.trendyol.stove.examples.kotlin.ktor.infra.boilerplate.kafka

import io.github.nomisRev.kafka.receiver.KafkaReceiver
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.flattenMerge
import org.apache.kafka.clients.consumer.ConsumerRecord

abstract class ConsumerSupervisor<K, V>(
  private val kafkaReceiver: KafkaReceiver<K, V>,
  private val maxConcurrency: Int
) {
  private val logger = KotlinLogging.logger("ConsumerSupervisor[${javaClass.simpleName}]")
  private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

  abstract val topics: List<String>

  fun start() = scope.launch {
    logger.info { "Receiving records from topics: $topics" }
    subscribe()
  }

  @OptIn(ExperimentalCoroutinesApi::class)
  @Suppress("TooGenericExceptionCaught")
  private suspend fun subscribe() {
    kafkaReceiver.receiveAutoAck(topics)
      .flattenMerge(maxConcurrency)
      .collect {
        try {
          consume(it)
        } catch (e: Exception) {
          handleError(e, it)
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
}
