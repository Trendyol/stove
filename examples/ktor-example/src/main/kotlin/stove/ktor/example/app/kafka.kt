package stove.ktor.example.app

import com.fasterxml.jackson.module.kotlin.readValue
import io.github.nomisRev.kafka.publisher.*
import io.github.nomisRev.kafka.receiver.*
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.common.serialization.*
import org.koin.core.module.Module
import org.koin.dsl.module
import stove.ktor.example.application.ExampleAppConsumer
import java.util.*

fun kafka(): Module = module {
  single { createReceiver<Any>(get()) }
  single { createPublisher(get()) }
  single { ExampleAppConsumer<String, Any>(get(), get()) }
}

private fun <V : Any> createReceiver(config: AppConfiguration): KafkaReceiver<String, V> {
  val settings = ReceiverSettings(
    config.kafka.bootstrapServers,
    StringDeserializer(),
    ExampleAppKafkaValueDeserializer<V>(),
    config.kafka.groupId,
    autoOffsetReset = AutoOffsetReset.Earliest,
    properties = Properties().apply {
      put(ConsumerConfig.INTERCEPTOR_CLASSES_CONFIG, config.kafka.interceptorClasses)
      put(ConsumerConfig.CLIENT_ID_CONFIG, config.kafka.clientId)
      put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, true)
      put(ConsumerConfig.AUTO_COMMIT_INTERVAL_MS_CONFIG, "1000")
      put(ConsumerConfig.ALLOW_AUTO_CREATE_TOPICS_CONFIG, true)
      put(ConsumerConfig.HEARTBEAT_INTERVAL_MS_CONFIG, "2000")
    }
  )
  return KafkaReceiver(settings)
}

private fun createPublisher(config: AppConfiguration): KafkaPublisher<String, Any> = PublisherSettings(
  config.kafka.bootstrapServers,
  StringSerializer(),
  ExampleAppKafkaValueSerializer(),
  properties = Properties().apply {
    put(ProducerConfig.INTERCEPTOR_CLASSES_CONFIG, config.kafka.interceptorClasses)
    put(ProducerConfig.CLIENT_ID_CONFIG, config.kafka.clientId)
  }
).let { KafkaPublisher(it) }

@Suppress("UNCHECKED_CAST")
class ExampleAppKafkaValueDeserializer<T : Any> : Deserializer<T> {
  override fun deserialize(
    topic: String,
    data: ByteArray
  ): T = objectMapperRef.readValue<Any>(data) as T
}

class ExampleAppKafkaValueSerializer<T : Any> : Serializer<T> {
  override fun serialize(
    topic: String,
    data: T
  ): ByteArray = objectMapperRef.writeValueAsBytes(data)
}
