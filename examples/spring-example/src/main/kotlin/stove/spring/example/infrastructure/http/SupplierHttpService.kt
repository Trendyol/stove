package stove.spring.example.infrastructure.http

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.http.*
import org.springframework.stereotype.Component
import stove.spring.example.application.services.*

@Component
class SupplierHttpService(
  private val supplierHttpClient: HttpClient
) : SupplierService {
  override suspend fun getSupplierPermission(id: Long): SupplierPermission =
    supplierHttpClient
      .get("/suppliers/$id/allowed") {
        contentType(ContentType.Application.Json)
      }.body()
}
