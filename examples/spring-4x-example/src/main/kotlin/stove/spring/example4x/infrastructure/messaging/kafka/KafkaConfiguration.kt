@file:Suppress("DEPRECATION")

package stove.spring.example4x.infrastructure.messaging.kafka

import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.common.serialization.*
import org.springframework.boot.context.properties.*
import org.springframework.context.annotation.*
import org.springframework.kafka.annotation.EnableKafka
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory
import org.springframework.kafka.core.*
import org.springframework.kafka.listener.RecordInterceptor
import org.springframework.kafka.support.serializer.*

@Configuration
@EnableKafka
@EnableConfigurationProperties(KafkaProperties::class)
class KafkaConfiguration {
  @Bean
  fun kafkaListenerContainerFactory(
    consumerFactory: ConsumerFactory<String, String>,
    interceptor: RecordInterceptor<String, String>?
  ): ConcurrentKafkaListenerContainerFactory<String, String> {
    val factory = ConcurrentKafkaListenerContainerFactory<String, String>()
    factory.setConsumerFactory(consumerFactory)
    interceptor?.let { factory.setRecordInterceptor(it) }
    return factory
  }

  @Bean
  @Suppress("MagicNumber")
  fun consumerFactory(
    config: KafkaProperties
  ): ConsumerFactory<String, String> = DefaultKafkaConsumerFactory(
    mapOf(
      ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG to config.bootstrapServers,
      ConsumerConfig.GROUP_ID_CONFIG to config.groupId,
      ConsumerConfig.AUTO_OFFSET_RESET_CONFIG to config.offset,
      ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG to StringDeserializer::class.java,
      ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG to ErrorHandlingDeserializer::class.java,
      ErrorHandlingDeserializer.VALUE_DESERIALIZER_CLASS to StringDeserializer::class.java,
      ConsumerConfig.HEARTBEAT_INTERVAL_MS_CONFIG to config.heartbeatInSeconds * 1000,
      ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG to config.heartbeatInSeconds * 3000,
      ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG to config.heartbeatInSeconds * 3000
    )
  )

  @Bean
  fun kafkaTemplate(
    config: KafkaProperties
  ): KafkaTemplate<String, Any> = KafkaTemplate(
    DefaultKafkaProducerFactory(
      mapOf(
        ProducerConfig.BOOTSTRAP_SERVERS_CONFIG to config.bootstrapServers,
        ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG to StringSerializer::class.java,
        ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG to JacksonJsonSerializer::class.java,
        ProducerConfig.ACKS_CONFIG to config.acks
      )
    )
  )
}

@ConfigurationProperties(prefix = "kafka")
data class KafkaProperties(
  val bootstrapServers: String,
  val groupId: String = "spring-4x-example",
  val offset: String = "earliest",
  val acks: String = "1",
  val heartbeatInSeconds: Int = 3,
  val topicPrefix: String = "trendyol.stove.service"
)
