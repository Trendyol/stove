package stove.micronaut.example.infrastructure.persistence

import io.r2dbc.spi.ConnectionFactory
import jakarta.inject.Singleton
import kotlinx.coroutines.reactive.awaitFirst
import kotlinx.coroutines.reactive.awaitFirstOrNull
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import stove.micronaut.example.application.domain.Product
import stove.micronaut.example.application.repository.ProductRepository
import java.util.*

@Singleton
class ProductJdbcRepository(
  private val connectionFactory: ConnectionFactory
) : ProductRepository {
  override suspend fun save(product: Product): Product {
    Mono
      .from(connectionFactory.create())
      .flatMap { connection ->
        Mono
          .from(
            connection
              .createStatement(
                """
            INSERT INTO products (id, name, supplier_id, is_blacklist, created_date) 
            VALUES ($1, $2, $3, $4, $5)
            """
              ).bind(INDEX_ID, product.id)
              .bind(INDEX_NAME, product.name)
              .bind(INDEX_SUPPLIER_ID, product.supplierId)
              .bind(INDEX_IS_BLACKLIST, product.isBlacklist)
              .bind(INDEX_CREATED_DATE, product.createdDate)
              .execute()
          ).doFinally { connection.close() }
      }.flatMap { result -> Mono.from(result.rowsUpdated) }
      .awaitFirst()

    return product
  }

  override suspend fun findById(id: Long): Product? =
    Mono
      .from(connectionFactory.create())
      .flatMapMany { connection ->
        Flux
          .from(
            connection
              .createStatement("SELECT * FROM products WHERE id = $1")
              .bind(INDEX_ID, id.toString())
              .execute()
          ).flatMap { result ->
            result.map { row, _ ->
              Product(
                id = row.get("id", String::class.java)!!,
                name = row.get("name", String::class.java)!!,
                supplierId = row.get("supplier_id", Long::class.java)!!,
                isBlacklist = row.get("is_blacklist", Boolean::class.java)!!,
                createdDate = row.get("created_date", Date::class.java)!!
              )
            }
          }.doFinally { connection.close() }
      }.next()
      .awaitFirstOrNull()

  companion object {
    private const val INDEX_ID = 0
    private const val INDEX_NAME = 1
    private const val INDEX_SUPPLIER_ID = 2
    private const val INDEX_IS_BLACKLIST = 3
    private const val INDEX_CREATED_DATE = 4
  }
}
