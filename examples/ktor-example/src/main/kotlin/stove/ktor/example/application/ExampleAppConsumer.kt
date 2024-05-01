package stove.ktor.example.application

import io.github.nomisRev.kafka.receiver.KafkaReceiver
import kotlinx.coroutines.*
import org.apache.kafka.clients.consumer.*
import stove.ktor.example.app.AppConfiguration
import java.time.Duration

@OptIn(DelicateCoroutinesApi::class)
class ExampleAppConsumer<K, V>(
  config: AppConfiguration,
  private val kafkaReceiver: KafkaReceiver<K, V>
) {
  private lateinit var job: Job

  private val scope = GlobalScope

  private val topics = config.kafka.topics.values.fold(listOf<String>()) { acc, topic -> acc + topic.topic + topic.error + topic.retry }

  companion object {
    private const val TIMEOUT_MS = 500L
  }

  fun start() {
    job = scope.launch {
      while (isActive) {
        kafkaReceiver
          .withConsumer { consumer ->
            consumer.subscribe(topics)
            consumer
              .poll(Duration.ofMillis(TIMEOUT_MS))
              .forEach { consume(it, consumer) }
          }
      }
    }
  }

  private fun consume(message: ConsumerRecord<K, V>, consumer: KafkaConsumer<K, V>) {
    println("Consumed message: $message")
    consumer.commitSync()
  }

  fun stop() {
    job.cancel()
  }
}
