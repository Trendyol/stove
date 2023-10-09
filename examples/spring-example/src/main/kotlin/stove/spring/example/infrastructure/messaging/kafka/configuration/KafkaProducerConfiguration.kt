package stove.spring.example.infrastructure.messaging.kafka.configuration

import org.apache.kafka.clients.producer.Producer
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.clients.producer.RecordMetadata
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.kafka.annotation.EnableKafka
import org.springframework.kafka.core.DefaultKafkaProducerFactory
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.core.ProducerFactory
import org.springframework.kafka.support.ProducerListener

@EnableKafka
@Configuration
class KafkaProducerConfiguration {
    private val logger = LoggerFactory.getLogger(javaClass)

    @Bean
    fun producer(producerFactory: ProducerFactory<String, Any>): Producer<String, Any> {
        return producerFactory.createProducer()
    }

    @Bean
    fun kafkaTemplate(producerFactory: ProducerFactory<String, Any>): KafkaTemplate<String, Any> {
        val kafkaTemplate = KafkaTemplate(producerFactory)
        kafkaTemplate.setProducerListener(
            object : ProducerListener<String, Any> {
                override fun onError(
                    producerRecord: ProducerRecord<String, Any>,
                    recordMetadata: RecordMetadata?,
                    exception: java.lang.Exception?
                ) {
                    logger.error(
                        "ProducerListener Topic: ${producerRecord.topic()}, Key: ${producerRecord.value()}",
                        exception
                    )
                    super.onError(producerRecord, recordMetadata, exception)
                }
            }
        )
        return kafkaTemplate
    }

    @Bean
    fun producerFactory(producerSettings: ProducerSettings): ProducerFactory<String, Any> =
        DefaultKafkaProducerFactory(producerSettings.settings())
}
