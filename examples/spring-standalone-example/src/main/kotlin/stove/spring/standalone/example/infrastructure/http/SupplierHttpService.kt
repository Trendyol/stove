package stove.spring.standalone.example.infrastructure.http

import kotlinx.coroutines.reactive.awaitFirst
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import stove.spring.standalone.example.application.services.*

@Component
class SupplierHttpService(private val supplierHttpClient: WebClient) : SupplierService {
  override suspend fun getSupplierPermission(id: Long): SupplierPermission = supplierHttpClient
    .get()
    .uri("/suppliers/$id/allowed")
    .accept(MediaType.APPLICATION_JSON)
    .retrieve()
    .bodyToMono(SupplierPermission::class.java)
    .awaitFirst()
}
