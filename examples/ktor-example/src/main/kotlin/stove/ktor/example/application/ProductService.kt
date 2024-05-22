package stove.ktor.example.application

import io.github.nomisRev.kafka.publisher.KafkaPublisher
import org.apache.kafka.clients.producer.ProducerRecord
import stove.ktor.example.domain.*
import java.time.Duration

class ProductService(
  private val repository: ProductRepository,
  private val lockProvider: LockProvider,
  private val kafkaPublisher: KafkaPublisher<String, Any>
) {
  companion object {
    private const val DURATION = 30L
  }

  suspend fun update(id: Int, request: UpdateProductRequest) {
    val acquireLock = lockProvider.acquireLock(::ProductService.name, Duration.ofSeconds(DURATION))

    if (!acquireLock) {
      print("lock could not be acquired")
      return
    }

    try {
      repository.transaction {
        val jedi = it.findById(id)
        jedi.name = request.name
        it.update(jedi)
      }

      kafkaPublisher.publishScope {
        offer(ProducerRecord("product", id.toString(), DomainEvents.ProductUpdated(id, request.name)))
      }
    } finally {
      lockProvider.releaseLock(::ProductService.name)
    }
  }
}
