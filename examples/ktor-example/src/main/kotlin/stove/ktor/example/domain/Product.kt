package stove.ktor.example.domain

data class Product(
  val id: Int,
  var name: String
)

object DomainEvents {
  data class ProductUpdated(
    val id: Int,
    val name: String,
    val price: Double = 0.0
  )
}
