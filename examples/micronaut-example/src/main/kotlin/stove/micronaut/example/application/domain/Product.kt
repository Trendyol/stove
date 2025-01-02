package stove.micronaut.example.application.domain

import io.micronaut.serde.annotation.Serdeable
import java.util.*

@Serdeable
data class Product(
  val id: String,
  val name: String,
  val supplierId: Long,
  val isBlacklist: Boolean,
  val createdDate: Date
) {
  companion object {

    fun new(id: String, name: String, supplierId: Long, isBlacklist: Boolean): Product {
      return Product(
        id = id,
        name = name,
        supplierId = supplierId,
        createdDate = Date(),
        isBlacklist = isBlacklist
      )
    }
  }
}
