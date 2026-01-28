package stove.spring.example4x.infrastructure.api

import io.opentelemetry.instrumentation.annotations.WithSpan
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import stove.spring.example4x.application.handlers.*
import stove.spring.example4x.infrastructure.messaging.kafka.KafkaProducer

@RestController
@RequestMapping("/api")
class ProductController(
  private val productCreator: ProductCreator,
  private val kafkaProducer: KafkaProducer
) {
  @GetMapping("/index")
  suspend fun index(
    @RequestParam(required = false) keyword: String?
  ): ResponseEntity<String> = ResponseEntity.ok("Hi from Stove framework with ${keyword ?: "no keyword"}")

  @WithSpan("ProductController.create")
  @PostMapping("/product/create")
  suspend fun create(
    @RequestBody request: ProductCreateRequest
  ): ResponseEntity<Any> {
    productCreator.create(request)
    kafkaProducer.send(ProductCreatedEvent(request.id, request.name, request.supplierId))
    return ResponseEntity.ok().build()
  }
}

data class ProductCreateRequest(
  val id: Long,
  val name: String,
  val supplierId: Long
)
