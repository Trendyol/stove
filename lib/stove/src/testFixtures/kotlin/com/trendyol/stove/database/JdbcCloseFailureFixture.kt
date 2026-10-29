package com.trendyol.stove.database

import java.lang.reflect.Proxy
import java.sql.Connection
import javax.sql.DataSource

/** A JDBC connection that records a close attempt and then fails, without opening a database connection. */
class JdbcCloseFailureFixture(val closeFailure: RuntimeException) {
  var closeAttempted: Boolean = false
    private set

  private val connection = Proxy.newProxyInstance(
    Connection::class.java.classLoader,
    arrayOf(Connection::class.java)
  ) { _, method, _ ->
    when (method.name) {
      "close" -> {
        closeAttempted = true
        throw closeFailure
      }

      "isClosed" -> closeAttempted

      "getAutoCommit" -> true

      else -> error("Unexpected JDBC connection method: ${method.name}")
    }
  } as Connection

  val dataSource: DataSource = Proxy.newProxyInstance(
    DataSource::class.java.classLoader,
    arrayOf(DataSource::class.java)
  ) { _, method, _ ->
    when (method.name) {
      "getConnection" -> connection
      else -> error("Unexpected JDBC data source method: ${method.name}")
    }
  } as DataSource
}
