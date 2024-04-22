package stove.ktor.example.domain

import io.r2dbc.postgresql.PostgresqlConnectionFactory
import io.r2dbc.postgresql.api.PostgresqlConnection
import kotlinx.coroutines.reactive.awaitFirst
import kotlinx.coroutines.reactive.awaitFirstOrNull
import kotlinx.coroutines.reactive.awaitSingle

class JediRepository(private val postgresqlConnectionFactory: PostgresqlConnectionFactory) {
  private lateinit var connection: PostgresqlConnection

  suspend fun findById(id: Long): Jedi {
    return connection.createStatement("SELECT * FROM Jedis WHERE id=$id").execute().awaitFirst().map { r, rm ->
      Jedi(
        (r.get(Jedi::id.name, rm.getColumnMetadata(Jedi::id.name).javaType!!) as Int).toLong(),
        r.get(Jedi::name.name, rm.getColumnMetadata(Jedi::name.name).javaType!!) as String
      )
    }.awaitSingle()
  }

  suspend fun update(jedi: Jedi) {
    connection.createStatement("UPDATE Jedis SET ${Jedi::name.name}=('${jedi.name}') WHERE ${Jedi::id.name}=${jedi.id}")
      .execute()
      .awaitFirstOrNull()
  }

  suspend fun transaction(invoke: suspend (JediRepository) -> Unit) {
    connection = this.postgresqlConnectionFactory.create().awaitFirst()
    connection.beginTransaction().awaitFirstOrNull()
    try {
      invoke(this)
      connection.commitTransaction().awaitFirstOrNull()
    } catch (ex: Exception) {
      connection.rollbackTransaction().awaitFirstOrNull()
      throw ex
    }
  }
}
