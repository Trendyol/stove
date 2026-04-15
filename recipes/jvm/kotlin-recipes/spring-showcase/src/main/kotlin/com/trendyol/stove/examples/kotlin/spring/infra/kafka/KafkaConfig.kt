package com.trendyol.stove.examples.kotlin.spring.infra.kafka

import com.fasterxml.jackson.databind.ObjectMapper
import com.trendyol.stove.examples.kotlin.spring.events.OrderCreatedEvent
import io.github.oshai.kotlinlogging.KotlinLogging
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.serialization.ByteArraySerializer
import org.apache.kafka.common.serialization.StringDeserializer
import org.apache.kafka.common.serialization.StringSerializer
import org.springframework.boot.autoconfigure.kafka.KafkaProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory
import org.springframework.kafka.core.ConsumerFactory
import org.springframework.kafka.core.DefaultKafkaConsumerFactory
import org.springframework.kafka.core.DefaultKafkaProducerFactory
import org.springframework.kafka.core.KafkaOperations
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.core.ProducerFactory
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer
import org.springframework.kafka.listener.DefaultErrorHandler
import org.springframework.kafka.support.serializer.JsonDeserializer
import org.springframework.kafka.support.serializer.JsonSerializer
import org.springframework.util.backoff.FixedBackOff

private val logger = KotlinLogging.logger {}

private const val DLQ_SUFFIX = ".DLT"
private const val MAX_RETRY_ATTEMPTS = 3L
private const val RETRY_INTERVAL_MS = 1000L

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
  fun kafkaTemplate(
    producerFactory: ProducerFactory<String, Any>
  ): KafkaTemplate<String, Any> = KafkaTemplate(producerFactory)

  /**
   * Dedicated producer factory for Dead Letter Queue.
   * Uses ByteArraySerializer to forward raw message bytes.
   */
  @Bean
  fun dlqProducerFactory(
    kafkaProperties: KafkaProperties
  ): ProducerFactory<String, ByteArray> {
    val props = kafkaProperties.buildProducerProperties(null).toMutableMap()
    props[ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG] = StringSerializer::class.java
    props[ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG] = ByteArraySerializer::class.java
    return DefaultKafkaProducerFactory(props)
  }

  @Bean
  fun dlqKafkaTemplate(
    dlqProducerFactory: ProducerFactory<String, ByteArray>
  ): KafkaTemplate<String, ByteArray> = KafkaTemplate(dlqProducerFactory)

  @Bean
  fun consumerFactory(
    kafkaProperties: KafkaProperties,
    objectMapper: ObjectMapper
  ): ConsumerFactory<String, OrderCreatedEvent> {
    // buildConsumerProperties includes spring.kafka.consumer.properties.* (e.g., interceptor.classes)
    val props = kafkaProperties.buildConsumerProperties(null).toMutableMap()
    props[ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG] = StringDeserializer::class.java
    props[ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG] = JsonDeserializer::class.java
    // Only set default if not already configured
    props.putIfAbsent(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest")

    val jsonDeserializer = JsonDeserializer(OrderCreatedEvent::class.java, objectMapper)
    jsonDeserializer.addTrustedPackages("*")

    return DefaultKafkaConsumerFactory(
      props,
      StringDeserializer(),
      jsonDeserializer
    )
  }

  /**
   * Dead Letter Publishing Recoverer - sends failed messages to DLQ topic.
   * Topic name: original-topic.DLT
   */
  @Bean
  fun deadLetterPublishingRecoverer(
    dlqKafkaTemplate: KafkaTemplate<String, ByteArray>
  ): DeadLetterPublishingRecoverer {
    @Suppress("UNCHECKED_CAST")
    val recoverer = DeadLetterPublishingRecoverer(
      dlqKafkaTemplate as KafkaOperations<Any, Any>
    ) { record: ConsumerRecord<*, *>, _: Exception ->
      TopicPartition("${record.topic()}$DLQ_SUFFIX", record.partition())
    }
    return recoverer
  }

  /**
   * Error handler with retry and dead letter queue support.
   * After MAX_RETRY_ATTEMPTS failures, message is sent to DLQ.
   */
  @Bean
  fun kafkaErrorHandler(
    deadLetterPublishingRecoverer: DeadLetterPublishingRecoverer
  ): DefaultErrorHandler {
    val errorHandler = DefaultErrorHandler(
      deadLetterPublishingRecoverer,
      FixedBackOff(RETRY_INTERVAL_MS, MAX_RETRY_ATTEMPTS)
    )
    // Log level is INFO by default, which is fine for our use case
    errorHandler.addNotRetryableExceptions(IllegalArgumentException::class.java)
    return errorHandler
  }

  @Bean
  fun kafkaListenerContainerFactory(
    consumerFactory: ConsumerFactory<String, OrderCreatedEvent>,
    kafkaErrorHandler: DefaultErrorHandler
  ): ConcurrentKafkaListenerContainerFactory<String, OrderCreatedEvent> {
    val factory = ConcurrentKafkaListenerContainerFactory<String, OrderCreatedEvent>()
    factory.consumerFactory = consumerFactory
    factory.setCommonErrorHandler(kafkaErrorHandler)
    return factory
  }
}
