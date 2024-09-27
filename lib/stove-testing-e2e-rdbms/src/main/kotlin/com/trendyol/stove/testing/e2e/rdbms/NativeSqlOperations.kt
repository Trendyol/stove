package com.trendyol.stove.testing.e2e.rdbms

import kotlinx.coroutines.flow.*
import org.jetbrains.exposed.sql.*
import java.sql.ResultSet

class NativeSqlOperations(database: Database) : AutoCloseable {
  private val connection = database.connector()

  fun execute(sql: String, parameters: Map<IColumnType<*>, Any> = mapOf()): Int = connection
    .prepareStatement(sql, false)
    .apply {
      timeout = Int.MAX_VALUE
      fillParameters(parameters.map { it.key to it.value })
    }
    .executeUpdate()

  suspend fun <T> select(
    sql: String,
    rowMapper: (ResultSet) -> T
  ): List<T> = connection
    .prepareStatement(sql, true)
    .executeQuery()
    .use { rs ->
      flow {
        while (rs.next()) {
          emit(rowMapper(rs))
        }
      }.toList()
    }

  override fun close() {
    connection.close()
  }
}
