package stove.spring.standalone.example.infrastructure.messaging.kafka.configuration

import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.common.serialization.StringSerializer
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.stereotype.Component
import stove.spring.standalone.example.infrastructure.messaging.kafka.interceptors.CustomProducerInterceptor

interface ProducerSettings : MapBasedSettings

@Component
@EnableConfigurationProperties(KafkaProperties::class)
class DefaultProducerSettings(private val kafkaProperties: KafkaProperties) : ProducerSettings {
  override fun settings(): Map<String, Any> {
    val props: MutableMap<String, Any> = HashMap()
    props[ProducerConfig.BOOTSTRAP_SERVERS_CONFIG] = kafkaProperties.bootstrapServers
    props[ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG] = StringSerializer::class.java
    props[ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG] = StringSerializer::class.java
    props[ProducerConfig.INTERCEPTOR_CLASSES_CONFIG] =
      listOf(CustomProducerInterceptor::class.java.name) + kafkaProperties.interceptorClasses
    props[ProducerConfig.ACKS_CONFIG] = kafkaProperties.acks
    props[ProducerConfig.COMPRESSION_TYPE_CONFIG] = kafkaProperties.compression
    props[ProducerConfig.CLIENT_ID_CONFIG] = kafkaProperties.createClientId()
    props[ProducerConfig.MAX_REQUEST_SIZE_CONFIG] = kafkaProperties.maxProducerConsumerBytes
    props["default.api.timeout.ms"] = kafkaProperties.defaultApiTimeout
    props[ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG] = kafkaProperties.requestTimeout
    return props
  }
}
