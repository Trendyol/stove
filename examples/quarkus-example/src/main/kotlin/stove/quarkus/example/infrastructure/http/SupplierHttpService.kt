package stove.quarkus.example.infrastructure.http

import com.fasterxml.jackson.databind.ObjectMapper
import io.opentelemetry.instrumentation.annotations.WithSpan
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.config.inject.ConfigProperty
import stove.quarkus.example.application.SupplierPermission
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

@ApplicationScoped
class SupplierHttpService(
  private val objectMapper: ObjectMapper
) {
  @ConfigProperty(name = "clients.supplier.url")
  lateinit var supplierBaseUrl: String

  private val httpClient: HttpClient = HttpClient
    .newBuilder()
    .connectTimeout(Duration.ofSeconds(SUPPLIER_CONNECT_TIMEOUT_SECONDS))
    .build()

  @WithSpan("SupplierHttpService.getSupplierPermission")
  fun getSupplierPermission(id: Long): SupplierPermission {
    val request = HttpRequest
      .newBuilder(URI.create("$supplierBaseUrl/suppliers/$id/allowed"))
      .header("Accept", "application/json")
      .GET()
      .build()

    val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
    check(response.statusCode() == HTTP_OK_STATUS) {
      "Supplier service returned ${response.statusCode()} for supplier $id"
    }

    return objectMapper.readValue(response.body(), SupplierPermission::class.java)
  }
}

private const val SUPPLIER_CONNECT_TIMEOUT_SECONDS = 2L
private const val HTTP_OK_STATUS = 200
