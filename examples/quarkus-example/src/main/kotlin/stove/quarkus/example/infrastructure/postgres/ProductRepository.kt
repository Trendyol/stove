package stove.quarkus.example.infrastructure.postgres

import io.opentelemetry.instrumentation.annotations.WithSpan
import jakarta.enterprise.context.ApplicationScoped
import stove.quarkus.example.application.ProductCreateRequest
import javax.sql.DataSource

@ApplicationScoped
class ProductRepository(
  private val dataSource: DataSource
) {
  @WithSpan("ProductRepository.save")
  fun save(request: ProductCreateRequest) {
    dataSource.connection.use { connection ->
      connection
        .prepareStatement(
          """
          INSERT INTO products (id, name, supplier_id)
          VALUES (?, ?, ?)
          """.trimIndent()
        ).use { statement ->
          statement.setLong(ID_PARAMETER_INDEX, request.id)
          statement.setString(NAME_PARAMETER_INDEX, request.name)
          statement.setLong(SUPPLIER_ID_PARAMETER_INDEX, request.supplierId)
          statement.executeUpdate()
        }
    }
  }
}

private const val ID_PARAMETER_INDEX = 1
private const val NAME_PARAMETER_INDEX = 2
private const val SUPPLIER_ID_PARAMETER_INDEX = 3
