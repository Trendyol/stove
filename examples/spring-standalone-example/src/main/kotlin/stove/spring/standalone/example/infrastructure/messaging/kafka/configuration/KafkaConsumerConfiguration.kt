package stove.spring.standalone.example.infrastructure.messaging.kafka.configuration

import org.springframework.context.annotation.*
import org.springframework.kafka.annotation.EnableKafka
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory
import org.springframework.kafka.core.*
import org.springframework.kafka.listener.*
import org.springframework.kafka.support.converter.StringJacksonJsonMessageConverter
import org.springframework.util.backoff.FixedBackOff
import tools.jackson.databind.json.JsonMapper

@EnableKafka
@Configuration
class KafkaConsumerConfiguration(
  private val jsonMapper: JsonMapper,
  private val interceptor: RecordInterceptor<String, String>
) {
  @Bean
  fun kafkaListenerContainerFactory(
    consumerFactory: ConsumerFactory<String, Any>,
    kafkaTemplate: KafkaTemplate<String, Any>
  ): ConcurrentKafkaListenerContainerFactory<String, String> {
    val factory = ConcurrentKafkaListenerContainerFactory<String, String>()
    factory.setConcurrency(1)
    factory.setConsumerFactory(consumerFactory)
    factory.containerProperties.isDeliveryAttemptHeader = true
    val errorHandler = DefaultErrorHandler(
      DeadLetterPublishingRecoverer(kafkaTemplate),
      FixedBackOff(0, 0)
    )
    factory.setCommonErrorHandler(errorHandler)
    factory.setRecordInterceptor(interceptor)
    return factory
  }

  @Bean
  fun kafkaRetryListenerContainerFactory(
    consumerRetryFactory: ConsumerFactory<String, Any>,
    kafkaTemplate: KafkaTemplate<String, Any>
  ): ConcurrentKafkaListenerContainerFactory<String, String> {
    val factory = ConcurrentKafkaListenerContainerFactory<String, String>()
    factory.setConcurrency(1)
    factory.containerProperties.isDeliveryAttemptHeader = true
    factory.setConsumerFactory(consumerRetryFactory)
    val errorHandler = DefaultErrorHandler(
      DeadLetterPublishingRecoverer(kafkaTemplate),
      FixedBackOff(INTERVAL, 1)
    )
    factory.setCommonErrorHandler(errorHandler)
    factory.setRecordInterceptor(interceptor)
    return factory
  }

  @Bean
  fun consumerFactory(consumerSettings: ConsumerSettings): ConsumerFactory<String, Any> =
    DefaultKafkaConsumerFactory(consumerSettings.settings())

  @Bean
  fun consumerRetryFactory(consumerSettings: ConsumerSettings): ConsumerFactory<String, Any> =
    DefaultKafkaConsumerFactory(consumerSettings.settings())

  @Bean
  fun stringJsonMessageConverter(): StringJacksonJsonMessageConverter = StringJacksonJsonMessageConverter(jsonMapper)

  companion object {
    const val RETRY_LISTENER_BEAN_NAME = "kafkaRetryListenerContainerFactory"
    const val LISTENER_BEAN_NAME = "kafkaListenerContainerFactory"
    const val INTERVAL = 5000L
  }
}
