package stove.ktor.example.application

import io.github.nomisRev.kafka.receiver.KafkaReceiver
import kotlinx.coroutines.*
import org.apache.kafka.clients.consumer.ConsumerRecord
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

  fun start() {
    job = scope.launch {
      while (!job.isCancelled) {
        kafkaReceiver
          .withConsumer { consumer ->
            consumer.subscribe(topics)
            consumer
              .poll(Duration.ofSeconds(1))
              .forEach { consume(it) }
          }
      }
    }
  }

  private fun consume(message: ConsumerRecord<K, V>) {
    println("Consumed message: $message")
  }

  fun stop() {
    job.cancel()
  }
}
