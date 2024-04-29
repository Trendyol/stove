package stove.ktor.example.domain

import io.r2dbc.postgresql.PostgresqlConnectionFactory
import io.r2dbc.postgresql.api.PostgresqlConnection
import kotlinx.coroutines.reactive.awaitFirst
import kotlinx.coroutines.reactive.awaitFirstOrNull
import kotlinx.coroutines.reactive.awaitSingle

class ProductRepository(private val postgresqlConnectionFactory: PostgresqlConnectionFactory) {
  private lateinit var connection: PostgresqlConnection

  suspend fun findById(id: Int): Product {
    return connection.createStatement("SELECT * FROM Products WHERE id=$id").execute().awaitFirst().map { r, rm ->
      Product(
        (r.get(Product::id.name, rm.getColumnMetadata(Product::id.name).javaType!!) as Int).toInt(),
        r.get(Product::name.name, rm.getColumnMetadata(Product::name.name).javaType!!) as String
      )
    }.awaitSingle()
  }

  suspend fun update(product: Product) {
    connection.createStatement("UPDATE Products SET ${Product::name.name}=('${product.name}') WHERE ${Product::id.name}=${product.id}")
      .execute()
      .awaitFirstOrNull()
  }

  suspend fun transaction(invoke: suspend (ProductRepository) -> Unit) {
    connection = this.postgresqlConnectionFactory.create().awaitFirst()
    connection.beginTransaction().awaitFirstOrNull()
    try {
      invoke(this)
      connection.commitTransaction().awaitFirstOrNull()
    } catch (
      @Suppress("TooGenericExceptionCaught") ex: Exception
    ) {
      connection.rollbackTransaction().awaitFirstOrNull()
      throw ex
    }
  }
}
