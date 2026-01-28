package stove.spring.example4x.infrastructure.messaging.kafka

import org.slf4j.*
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.messaging.handler.annotation.*
import org.springframework.stereotype.Component
import stove.spring.example4x.application.handlers.ProductCreator
import stove.spring.example4x.infrastructure.api.ProductCreateRequest
import tools.jackson.databind.json.JsonMapper

@Component
class ProductCreateConsumer(
  private val productCreator: ProductCreator,
  private val jsonMapper: JsonMapper
) {
  private val logger: Logger = LoggerFactory.getLogger(javaClass)

  @KafkaListener(topics = ["trendyol.stove.service.product.create.0"], groupId = "\${kafka.groupId}")
  suspend fun consume(
    @Payload message: String,
    @Header("X-UserEmail", required = false) userEmail: String?
  ) {
    logger.info("Received message: $message with userEmail: $userEmail")
    val command = jsonMapper.readValue(message, CreateProductCommand::class.java)
    productCreator.create(ProductCreateRequest(command.id, command.name, command.supplierId))
  }
}

@Component
class ProductEventsConsumer {
  private val logger: Logger = LoggerFactory.getLogger(javaClass)

  @KafkaListener(
    topics = ["trendyol.stove.service.productCreated.1"],
    groupId = "\${kafka.groupId}",
    containerFactory = "kafkaListenerContainerFactory"
  )
  fun consumeProductCreatedEvent(
    @Payload message: String
  ) {
    logger.info("Received message: $message")
  }
}

data class CreateProductCommand(
  val id: Long,
  val name: String,
  val supplierId: Long
)
