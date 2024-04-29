package stove.ktor.example.domain

data class Product(
  val id: Int,
  var name: String
)

object DomainEvents {
  data class ProductUpdated(val id: Int, val name: String)

  data class ProductCreated(val id: Int, val name: String)

  data class ProductDeleted(val id: Int)
}
