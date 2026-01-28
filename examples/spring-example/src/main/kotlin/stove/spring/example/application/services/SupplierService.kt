package stove.spring.example.application.services

data class SupplierPermission(
  val supplierId: Long,
  val isAllowed: Boolean
)

interface SupplierService {
  suspend fun getSupplierPermission(id: Long): SupplierPermission
}
