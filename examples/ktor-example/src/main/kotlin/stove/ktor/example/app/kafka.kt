package stove.ktor.example.app

import com.fasterxml.jackson.module.kotlin.readValue
import org.apache.kafka.clients.consumer.*
import org.apache.kafka.clients.producer.*
import org.apache.kafka.common.serialization.*
import org.koin.core.module.Module
import org.koin.dsl.module
import stove.ktor.example.application.*
import kotlin.time.Duration.Companion.seconds

fun kafka(): Module = module {
  single { createConsumer<Any>(get()) }
  single { createProducer(get()) }
  single { ExampleAppConsumer<String, Any>(get(), get()) }
}

@Suppress("MagicNumber")
private fun <V : Any> createConsumer(config: AppConfiguration): KafkaConsumer<String, V> {
  val pollTimeoutSec = POLL_TIMEOUT_SECONDS
  val heartbeatSec = pollTimeoutSec + 1
  return KafkaConsumer(
    mapOf(
      ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG to config.kafka.bootstrapServers,
      ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG to StringDeserializer::class.java,
      ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG to ExampleAppKafkaValueDeserializer::class.java,
      ConsumerConfig.GROUP_ID_CONFIG to config.kafka.groupId,
      ConsumerConfig.AUTO_OFFSET_RESET_CONFIG to "earliest",
      ConsumerConfig.INTERCEPTOR_CLASSES_CONFIG to config.kafka.interceptorClasses,
      ConsumerConfig.CLIENT_ID_CONFIG to config.kafka.clientId,
      ConsumerConfig.ALLOW_AUTO_CREATE_TOPICS_CONFIG to true,
      ConsumerConfig.HEARTBEAT_INTERVAL_MS_CONFIG to heartbeatSec.seconds.inWholeSeconds.toInt(),
      ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG to true
    )
  )
}

private fun createProducer(config: AppConfiguration): KafkaProducer<String, Any> = KafkaProducer(
  mapOf(
    ProducerConfig.BOOTSTRAP_SERVERS_CONFIG to config.kafka.bootstrapServers,
    ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG to StringSerializer::class.java,
    ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG to ExampleAppKafkaValueSerializer::class.java,
    ProducerConfig.INTERCEPTOR_CLASSES_CONFIG to config.kafka.interceptorClasses,
    ProducerConfig.CLIENT_ID_CONFIG to config.kafka.clientId
  )
)

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
