package stove.quarkus.example.application

data class ProductCreateRequest(
  val id: Long,
  val name: String,
  val supplierId: Long
)

data class ProductCreatedEvent(
  val id: Long,
  val name: String,
  val supplierId: Long
)

data class CreateProductCommand(
  val id: Long,
  val name: String,
  val supplierId: Long
)

data class SupplierPermission(
  val supplierId: Long,
  val isAllowed: Boolean
)

fun CreateProductCommand.toCreateRequest(): ProductCreateRequest = ProductCreateRequest(
  id = id,
  name = name,
  supplierId = supplierId
)

fun ProductCreateRequest.toProductCreatedEvent(): ProductCreatedEvent = ProductCreatedEvent(
  id = id,
  name = name,
  supplierId = supplierId
)
