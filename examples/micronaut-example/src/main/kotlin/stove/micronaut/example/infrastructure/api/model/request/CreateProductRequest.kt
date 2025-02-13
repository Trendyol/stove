package stove.micronaut.example.infrastructure.api.model.request

import io.micronaut.serde.annotation.Serdeable

@Serdeable
data class CreateProductRequest(
  val id: String,
  val name: String,
  val supplierId: Long
)
