package stove.ktor.example.application

import io.github.nomisRev.kafka.receiver.KafkaReceiver
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.onEach
import org.apache.kafka.clients.consumer.ConsumerRecord
import stove.ktor.example.app.AppConfiguration

class ExampleAppConsumer<K, V>(
  config: AppConfiguration,
  private val kafkaReceiver: KafkaReceiver<K, V>
) {
  private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
  private val topics = config.kafka.topics.values.fold(listOf<String>()) { acc, topic -> acc + topic.topic + topic.error + topic.retry }

  fun start() = scope.launch {
    kafkaReceiver
      .receive(topics)
      .onEach {
        // We don't want tests to wait
        it.offset.commit()
      }
      .collect(::consume)
  }

  private fun consume(message: ConsumerRecord<K, V>) {
    println("Consumed message: $message")
  }

  fun stop() = scope.cancel()
}
