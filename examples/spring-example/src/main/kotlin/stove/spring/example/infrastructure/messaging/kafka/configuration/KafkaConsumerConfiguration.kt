package stove.spring.example.infrastructure.messaging.kafka.configuration

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.kafka.annotation.EnableKafka
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory
import org.springframework.kafka.core.ConsumerFactory
import org.springframework.kafka.core.DefaultKafkaConsumerFactory
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.listener.ConsumerAwareRecordInterceptor
import org.springframework.kafka.listener.DefaultErrorHandler
import org.springframework.kafka.support.converter.StringJsonMessageConverter
import org.springframework.util.backoff.FixedBackOff

@EnableKafka
@Configuration
class KafkaConsumerConfiguration(
    private val objectMapper: ObjectMapper,
    private val customConsumerInterceptor: ConsumerAwareRecordInterceptor<String, String>
) {

    @Bean
    fun kafkaListenerContainerFactory(
        kafkaTemplate: KafkaTemplate<String, Any>,
        consumerFactory: ConsumerFactory<String, Any>
    ): ConcurrentKafkaListenerContainerFactory<String, String> {
        val factory = ConcurrentKafkaListenerContainerFactory<String, String>()
        factory.setConcurrency(1)
        factory.consumerFactory = consumerFactory
        factory.containerProperties.isDeliveryAttemptHeader = true
        factory.setRecordMessageConverter(stringJsonMessageConverter())
        val errorHandler = DefaultErrorHandler(
            FixedBackOff(0, 0)
        )

        factory.setCommonErrorHandler(errorHandler)
        factory.setRecordInterceptor(customConsumerInterceptor)
        return factory
    }

    @Bean
    fun kafkaRetryListenerContainerFactory(
        kafkaTemplate: KafkaTemplate<*, *>,
        consumerRetryFactory: ConsumerFactory<String, Any>
    ): ConcurrentKafkaListenerContainerFactory<String, String> {
        val factory = ConcurrentKafkaListenerContainerFactory<String, String>()
        factory.setConcurrency(1)
        factory.setRecordMessageConverter(stringJsonMessageConverter())
        factory.containerProperties.isDeliveryAttemptHeader = true
        factory.consumerFactory = consumerRetryFactory
        val errorHandler = DefaultErrorHandler(
            FixedBackOff(5000, 1)
        )
        factory.setCommonErrorHandler(errorHandler)
        factory.setRecordInterceptor(customConsumerInterceptor)
        return factory
    }

    @Bean
    fun consumerFactory(consumerSettings: ConsumerSettings): ConsumerFactory<String, Any> {
        return DefaultKafkaConsumerFactory(consumerSettings.settings())
    }

    @Bean
    fun consumerRetryFactory(consumerSettings: ConsumerSettings): ConsumerFactory<String, Any> {
        return DefaultKafkaConsumerFactory(consumerSettings.settings())
    }

    @Bean
    fun stringJsonMessageConverter(): StringJsonMessageConverter {
        return StringJsonMessageConverter(objectMapper)
    }
    companion object {
        const val RETRY_LISTENER_BEAN_NAME = "kafkaRetryListenerContainerFactory"
        const val LISTENER_BEAN_NAME = "kafkaListenerContainerFactory"
    }
}
