package stove.spring.streams.example.kafka

import org.apache.kafka.clients.consumer.ConsumerConfig.INTERCEPTOR_CLASSES_CONFIG
import org.apache.kafka.common.serialization.Serdes
import org.apache.kafka.streams.StreamsConfig.APPLICATION_ID_CONFIG
import org.apache.kafka.streams.StreamsConfig.BOOTSTRAP_SERVERS_CONFIG
import org.apache.kafka.streams.StreamsConfig.COMMIT_INTERVAL_MS_CONFIG
import org.apache.kafka.streams.StreamsConfig.DEFAULT_DESERIALIZATION_EXCEPTION_HANDLER_CLASS_CONFIG
import org.apache.kafka.streams.StreamsConfig.DEFAULT_KEY_SERDE_CLASS_CONFIG
import org.apache.kafka.streams.StreamsConfig.DEFAULT_PRODUCTION_EXCEPTION_HANDLER_CLASS_CONFIG
import org.apache.kafka.streams.StreamsConfig.DEFAULT_VALUE_SERDE_CLASS_CONFIG
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.kafka.annotation.EnableKafka
import org.springframework.kafka.annotation.EnableKafkaStreams
import org.springframework.kafka.annotation.KafkaStreamsDefaultConfiguration
import org.springframework.kafka.config.KafkaStreamsConfiguration

@Configuration
@EnableKafka
@EnableKafkaStreams
class StreamsConfig {
  @Value("\${spring.kafka.streams.bootstrap-servers}")
  val bootstrapServers: String = ""

  @Value("\${kafka.interceptorClasses}")
  val interceptorClass = emptyList<String>()

  @Bean(name = [KafkaStreamsDefaultConfiguration.DEFAULT_STREAMS_CONFIG_BEAN_NAME])
  fun kStreamsConfig(): KafkaStreamsConfiguration {
    val props: MutableMap<String, Any?> = HashMap()
    props[APPLICATION_ID_CONFIG] = "stove.example"
    props[BOOTSTRAP_SERVERS_CONFIG] = bootstrapServers
    props[DEFAULT_KEY_SERDE_CLASS_CONFIG] = Serdes.String().javaClass.name
    props[DEFAULT_VALUE_SERDE_CLASS_CONFIG] = Serdes.String().javaClass.name
    props[COMMIT_INTERVAL_MS_CONFIG] = 0
    props[DEFAULT_DESERIALIZATION_EXCEPTION_HANDLER_CLASS_CONFIG] = CustomDeserializationExceptionHandler::class.java
    props[DEFAULT_PRODUCTION_EXCEPTION_HANDLER_CLASS_CONFIG] = CustomProductionExceptionHandler::class.java
    props[INTERCEPTOR_CLASSES_CONFIG] = interceptorClass

    return KafkaStreamsConfiguration(props)
  }
}
