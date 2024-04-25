package stove.ktor.example.application

import stove.ktor.example.domain.ProductRepository
import java.time.Duration

class ProductService(
  private val repository: ProductRepository,
  private val lockProvider: LockProvider
) {
  companion object {
    private const val DURATION = 30L
  }

  suspend fun update(id: Long, request: UpdateProductRequest) {
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
    } finally {
      lockProvider.releaseLock(::ProductService.name)
    }
  }
}
