package com.trendyol.stove.examples.kotlin.spring.infra.kafka

import com.fasterxml.jackson.databind.ObjectMapper
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.common.serialization.StringSerializer
import org.springframework.boot.autoconfigure.kafka.KafkaProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.kafka.core.DefaultKafkaProducerFactory
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.core.ProducerFactory
import org.springframework.kafka.support.serializer.JsonSerializer

@Configuration
class KafkaConfig {

  @Bean
  fun producerFactory(
    kafkaProperties: KafkaProperties,
    objectMapper: ObjectMapper
  ): ProducerFactory<String, Any> {
    val props = kafkaProperties.buildProducerProperties(null).toMutableMap()
    props[ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG] = StringSerializer::class.java
    props[ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG] = JsonSerializer::class.java

    val factory = DefaultKafkaProducerFactory<String, Any>(props)
    factory.setValueSerializer(JsonSerializer(objectMapper))
    return factory
  }

  @Bean
  fun kafkaTemplate(producerFactory: ProducerFactory<String, Any>): KafkaTemplate<String, Any> {
    return KafkaTemplate(producerFactory)
  }
}
