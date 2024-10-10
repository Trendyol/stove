package stove.ktor.example.application

import org.apache.kafka.clients.producer.*
import stove.ktor.example.domain.*
import java.time.Duration
import kotlin.coroutines.*

class ProductService(
  private val repository: ProductRepository,
  private val lockProvider: LockProvider,
  private val kafkaProducer: KafkaProducer<String, Any>
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
      suspendCoroutine {
        kafkaProducer
          .send(ProducerRecord("product", id.toString(), DomainEvents.ProductUpdated(id, request.name))) { _, exception ->
            if (exception != null) {
              it.resumeWithException(exception)
            } else {
              it.resume(Unit)
            }
          }
      }
    } finally {
      lockProvider.releaseLock(::ProductService.name)
    }
  }
}
