package stove.spring.example.application.handlers

import kotlinx.coroutines.reactive.awaitFirst
import org.springframework.beans.factory.annotation.Value
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Component
import stove.spring.example.infrastructure.Headers
import stove.spring.example.infrastructure.http.SupplierHttpService
import stove.spring.example.infrastructure.messaging.kafka.*
import stove.spring.example.infrastructure.messaging.kafka.consumers.CreateProductCommand
import java.time.Instant

@Component
class ProductCreator(
  private val supplierHttpService: SupplierHttpService,
  private val databaseClient: DatabaseClient,
  private val kafkaProducer: KafkaProducer
) {
  @Value("\${kafka.producer.product-created.topic-name}")
  lateinit var productCreatedTopic: String

  suspend fun create(req: ProductCreateRequest): String {
    val supplierPermission = supplierHttpService.getSupplierPermission(req.id)
    if (!supplierPermission.isAllowed) {
      return "Supplier with the given id(${req.supplierId}) is not allowed for product creation"
    }

    databaseClient
      .sql(
        """
      INSERT INTO products (id, name, supplier_id, created_date) 
      VALUES (:id, :name, :supplierId, :createdDate)
      """
      ).bind("id", req.id)
      .bind("name", req.name)
      .bind("supplierId", req.supplierId)
      .bind("createdDate", Instant.now())
      .fetch()
      .rowsUpdated()
      .awaitFirst()

    kafkaProducer.send(
      KafkaOutgoingMessage(
        topic = productCreatedTopic,
        key = req.id.toString(),
        headers = mapOf(Headers.EVENT_TYPE to ProductCreatedEvent::class.simpleName!!),
        partition = 0,
        payload = req.mapToProductCreatedEvent()
      )
    )
    return "OK"
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
