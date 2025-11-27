package stove.spring.standalone.example.application.handlers

import com.couchbase.client.java.ReactiveCollection
import com.couchbase.client.java.json.JsonObject
import kotlinx.coroutines.reactive.awaitFirst
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import stove.spring.standalone.example.infrastructure.Headers
import stove.spring.standalone.example.infrastructure.http.SupplierHttpService
import stove.spring.standalone.example.infrastructure.messaging.kafka.*
import stove.spring.standalone.example.infrastructure.messaging.kafka.consumers.CreateProductCommand
import tools.jackson.databind.json.JsonMapper
import java.util.*

@Component
class ProductCreator(
  private val supplierHttpService: SupplierHttpService,
  private val collection: ReactiveCollection,
  private val jsonMapper: JsonMapper,
  private val kafkaProducer: KafkaProducer
) {
  @Value("\${kafka.producer.product-created.topic-name}")
  lateinit var productCreatedTopic: String

  suspend fun create(req: ProductCreateRequest): String {
    val supplierPermission = supplierHttpService.getSupplierPermission(req.supplierId)
    if (!supplierPermission.isAllowed) return "Supplier with the given id(${req.supplierId}) is not allowed for product creation"
    val fromJson = JsonObject.fromJson(jsonMapper.writeValueAsString(req))

    collection.insert("product:${req.id}", fromJson).awaitFirst()

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

fun ProductCreateRequest.mapToProductCreatedEvent(): ProductCreatedEvent = ProductCreatedEvent(this.id, this.name, this.supplierId, Date())

data class ProductCreatedEvent(
  val id: Long,
  val name: String,
  val supplierId: Long,
  val createdDate: Date,
  val type: String = ProductCreatedEvent::class.simpleName!!
)

data class ProductCreateRequest(
  val id: Long,
  val name: String,
  val supplierId: Long
)
