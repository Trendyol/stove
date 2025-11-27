package stove.spring.example.infrastructure.messaging.kafka.configuration

import org.springframework.context.annotation.*
import org.springframework.kafka.annotation.EnableKafka
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory
import org.springframework.kafka.core.*
import org.springframework.kafka.listener.*
import org.springframework.util.backoff.FixedBackOff

@EnableKafka
@Configuration
@Suppress("UNCHECKED_CAST")
class KafkaConsumerConfiguration(
  private val interceptor: RecordInterceptor<*, *>
) {
  @Bean
  fun kafkaListenerContainerFactory(
    consumerFactory: ConsumerFactory<String, Any>
  ): ConcurrentKafkaListenerContainerFactory<String, String> {
    val factory = ConcurrentKafkaListenerContainerFactory<String, String>()
    factory.setConcurrency(1)
    factory.setConsumerFactory(consumerFactory)
    factory.containerProperties.isDeliveryAttemptHeader = true
    val errorHandler = DefaultErrorHandler(FixedBackOff(0, 0))
    factory.setCommonErrorHandler(errorHandler)
    factory.setRecordInterceptor(interceptor as RecordInterceptor<String, String>)
    return factory
  }

  @Bean
  fun kafkaRetryListenerContainerFactory(
    consumerRetryFactory: ConsumerFactory<String, Any>
  ): ConcurrentKafkaListenerContainerFactory<String, String> {
    val factory = ConcurrentKafkaListenerContainerFactory<String, String>()
    factory.setConcurrency(1)
    factory.containerProperties.isDeliveryAttemptHeader = true
    factory.setConsumerFactory(consumerRetryFactory)
    val errorHandler = DefaultErrorHandler(FixedBackOff(INTERVAL, 1))
    factory.setCommonErrorHandler(errorHandler)
    factory.setRecordInterceptor(interceptor as RecordInterceptor<String, String>)
    return factory
  }

  @Bean
  fun consumerFactory(
    consumerSettings: ConsumerSettings
  ): ConsumerFactory<String, Any> = DefaultKafkaConsumerFactory(consumerSettings.settings())

  @Bean
  fun consumerRetryFactory(
    consumerSettings: ConsumerSettings
  ): ConsumerFactory<String, Any> = DefaultKafkaConsumerFactory(consumerSettings.settings())

  companion object {
    const val RETRY_LISTENER_BEAN_NAME = "kafkaRetryListenerContainerFactory"
    const val LISTENER_BEAN_NAME = "kafkaListenerContainerFactory"
    const val INTERVAL = 5000L
  }
}
