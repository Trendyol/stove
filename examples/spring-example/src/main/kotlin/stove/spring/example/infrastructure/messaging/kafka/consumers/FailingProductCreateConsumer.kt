package stove.spring.example.infrastructure.messaging.kafka.consumers

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.slf4j.MDCContext
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component
import stove.spring.example.infrastructure.messaging.kafka.configuration.KafkaConsumerConfiguration

data class BusinessException(override val message: String) : RuntimeException(message)

data class FailingEvent(val id: Long)

@Component
@ConditionalOnProperty(prefix = "kafka.consumers", value = ["enabled"], havingValue = "true")
class FailingProductCreateConsumer {
  private val logger = LoggerFactory.getLogger(javaClass)

  @KafkaListener(
    topics = ["#{@productFailingEventTopicConfig.topic}"],
    groupId = "#{@consumerConfig.groupId}",
    containerFactory = KafkaConsumerConfiguration.LISTENER_BEAN_NAME
  )
  fun listen(record: ConsumerRecord<*, *>): Unit = runBlocking(MDCContext()) {
    logger.info("Received product failing event $record")
    throw BusinessException("Failing product create event")
  }
}
