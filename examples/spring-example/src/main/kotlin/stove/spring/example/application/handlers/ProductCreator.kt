package stove.spring.example.application.handlers

import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import stove.spring.example.domain.Products
import stove.spring.example.infrastructure.Headers
import stove.spring.example.infrastructure.http.SupplierHttpService
import stove.spring.example.infrastructure.messaging.kafka.*
import stove.spring.example.infrastructure.messaging.kafka.consumers.CreateProductCommand
import java.time.Instant

@Component
class ProductCreator(
  private val supplierHttpService: SupplierHttpService,
  private val kafkaProducer: KafkaProducer
) {
  @Value("\${kafka.producer.product-created.topic-name}")
  lateinit var productCreatedTopic: String

  suspend fun create(req: ProductCreateRequest): String = suspendTransaction {
    val supplierPermission = supplierHttpService.getSupplierPermission(req.supplierId)
    if (!supplierPermission.isAllowed) {
      return@suspendTransaction "Supplier with the given id(${req.supplierId}) is not allowed for product creation"
    }

    Products.insert {
      it[id] = req.id
      it[name] = req.name
      it[supplierId] = req.supplierId
      it[Products.createdDate] = Instant.now()
    }

    kafkaProducer.send(
      KafkaOutgoingMessage(
        topic = productCreatedTopic,
        key = req.id.toString(),
        headers = mapOf(Headers.EVENT_TYPE to ProductCreatedEvent::class.simpleName!!),
        partition = 0,
        payload = req.mapToProductCreatedEvent()
      )
    )
    return@suspendTransaction "OK"
  }
}

fun CreateProductCommand.mapToCreateRequest(): ProductCreateRequest = ProductCreateRequest(this.id, this.name, this.supplierId)

fun ProductCreateRequest.mapToProductCreatedEvent(): ProductCreatedEvent = ProductCreatedEvent(
  this.id,
  this.name,
  this.supplierId,
  Instant.now()
)

data class ProductCreatedEvent(
  val id: Long,
  val name: String,
  val supplierId: Long,
  val createdDate: Instant,
  val type: String = ProductCreatedEvent::class.simpleName!!
)

data class ProductCreateRequest(
  val id: Long,
  val name: String,
  val supplierId: Long
)
