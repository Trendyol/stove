package stove.micronaut.example.application.services

import io.micronaut.serde.annotation.Serdeable

@Serdeable
data class SupplierPermission(
  val id: Long,
  val isBlacklisted: Boolean
)

interface SupplierService {
  suspend fun getSupplierPermission(supplierId: Long): SupplierPermission?
}
