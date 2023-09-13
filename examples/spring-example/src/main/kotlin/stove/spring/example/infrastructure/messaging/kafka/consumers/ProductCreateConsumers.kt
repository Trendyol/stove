package stove.spring.example.infrastructure.messaging.kafka.consumers

import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.slf4j.MDCContext
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component
import stove.spring.example.application.handlers.ProductCreator
import stove.spring.example.application.handlers.mapToCreateRequest
import stove.spring.example.infrastructure.messaging.kafka.configuration.KafkaConsumerConfiguration

@Component
@ConditionalOnProperty(prefix = "kafka.consumers", value = ["enabled"], havingValue = "true")
class ProductTransferConsumers(
    private val objectMapper: ObjectMapper,
    private val productCreator: ProductCreator
) {
    private val logger = LoggerFactory.getLogger(ProductTransferConsumers::class.java)

    @KafkaListener(
        topics = ["#{@productCreateEventTopicConfig.topic}"],
        groupId = "#{@consumerConfig.groupId}",
        containerFactory = KafkaConsumerConfiguration.LISTENER_BEAN_NAME
    )
    @KafkaListener(
        topics = ["#{@productCreateEventTopicConfig.retryTopic}"],
        groupId = "#{@consumerConfig.groupId}_retry",
        containerFactory = KafkaConsumerConfiguration.RETRY_LISTENER_BEAN_NAME
    )
    fun listen(cr: ConsumerRecord<String, String>) = runBlocking(MDCContext()) {
        logger.info("Received product transfer command ${cr.value()}")
        val command = objectMapper.readValue(
            cr.value(),
            CreateProductCommand::class.java
        )
        productCreator.create(command.mapToCreateRequest())
    }
}

data class CreateProductCommand(
    val id: Long,
    val name: String,
    val supplierId: Long
)
