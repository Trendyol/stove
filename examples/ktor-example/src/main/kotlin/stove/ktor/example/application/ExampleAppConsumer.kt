package stove.ktor.example.application

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.apache.kafka.clients.consumer.*
import stove.ktor.example.app.AppConfiguration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

const val POLL_TIMEOUT_SECONDS = 2

class ExampleAppConsumer<K, V>(
  config: AppConfiguration,
  kafkaConsumer: KafkaConsumer<K, V>
) {
  private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
  private val topics = config.kafka.topics.values
    .fold(listOf<String>()) { acc, topic -> acc + topic.topic + topic.error + topic.retry }

  private val subscription = kafkaConsumer
    .apply { subscribe(topics) }

  fun start() {
    loop()
  }

  private fun loop() {
    channelFlow {
      while (isActive) {
        val records = subscription.poll(POLL_TIMEOUT_SECONDS.seconds.toJavaDuration())
        for (record in records) {
          send(record)
        }
      }
    }.onEach { consume(it) }
      .catch { exception -> throw exception }
      .launchIn(scope)
  }

  fun stop() = scope.cancel()

  private fun consume(message: ConsumerRecord<K, V>) {
    println("Consumed message: $message")
  }
}
