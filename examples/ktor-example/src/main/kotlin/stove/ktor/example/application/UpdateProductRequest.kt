package stove.ktor.example.application

import kotlinx.serialization.Serializable

@Serializable
data class UpdateProductRequest(
  val name: String
)
