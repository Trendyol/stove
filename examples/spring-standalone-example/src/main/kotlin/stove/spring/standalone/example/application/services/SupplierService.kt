package stove.spring.standalone.example.application.services

data class SupplierPermission(
  val id: Long,
  val isAllowed: Boolean
)

interface SupplierService {
  suspend fun getSupplierPermission(id: Long): SupplierPermission?
}
