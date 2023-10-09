package stove.spring.example.infrastructure.http

import kotlinx.coroutines.reactive.awaitFirst
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import stove.spring.example.application.services.SupplierPermission
import stove.spring.example.application.services.SupplierService

@Component
class SupplierHttpService(private val supplierHttpClient: WebClient) : SupplierService {
    override suspend fun getSupplierPermission(id: Long): SupplierPermission {
        return supplierHttpClient
            .get()
            .uri {
                val builder =
                    it
                        .path("/suppliers/{id}/allowed")
                builder.build(id)
            }
            .accept(MediaType.APPLICATION_JSON)
            .retrieve()
            .bodyToMono(SupplierPermission::class.java)
            .awaitFirst()
    }
}
