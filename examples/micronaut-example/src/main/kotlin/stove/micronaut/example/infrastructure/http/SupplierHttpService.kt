package stove.micronaut.example.infrastructure.http

import io.micronaut.http.annotation.Get
import io.micronaut.http.client.annotation.Client
import io.micronaut.websocket.exceptions.WebSocketClientException
import jakarta.inject.Singleton
import stove.micronaut.example.application.services.SupplierPermission
import stove.micronaut.example.application.services.SupplierService

@Singleton
class SupplierHttpService(
  private val supplierHttpClient: SupplierHttpClient
) : SupplierService {
  override suspend fun getSupplierPermission(supplierId: Long): SupplierPermission? = try {
    val response = supplierHttpClient.getSupplierPermission(supplierId)
    println("API Response: $response") // Yanıtı konsola yazdır
    response
  } catch (e: WebSocketClientException) {
    println("Error fetching supplier permission: ${e.message}")
    null
  }
}

@Client(id = "lookup-api")
interface SupplierHttpClient {
  @Get("/v2/suppliers/{supplierId}?storeFrontId=1")
  suspend fun getSupplierPermission(supplierId: Long): SupplierPermission
}
