package com.trendyol.stove.database

import java.lang.reflect.Proxy
import java.sql.Connection
import javax.sql.DataSource

/** Supplies a SQL session's connection and records failure-tolerant cleanup without opening a database. */
class JdbcCloseFailureFixture {
  val events: MutableList<String> = mutableListOf()
  val cleanupFailure: IllegalStateException = IllegalStateException("cleanup failed")
  val closeFailure: IllegalStateException = IllegalStateException("connection close failed")
  private var closed = false

  private val connection = Proxy.newProxyInstance(
    Connection::class.java.classLoader,
    arrayOf(Connection::class.java)
  ) { proxy, method, arguments ->
    when (method.name) {
      "close" -> {
        events += "connection.close"
        closed = true
        throw closeFailure
      }

      "isClosed" -> closed

      "getAutoCommit" -> true

      "toString" -> "CleanupTestConnection"

      "hashCode" -> System.identityHashCode(proxy)

      "equals" -> proxy === arguments?.single()

      else -> error("Unexpected JDBC call: ${method.name}")
    }
  } as Connection

  val dataSource: DataSource = Proxy.newProxyInstance(
    DataSource::class.java.classLoader,
    arrayOf(DataSource::class.java)
  ) { proxy, method, arguments ->
    when (method.name) {
      "getConnection" -> connection
      "toString" -> "CleanupTestDataSource"
      "hashCode" -> System.identityHashCode(proxy)
      "equals" -> proxy === arguments?.single()
      else -> error("Unexpected DataSource call: ${method.name}")
    }
  } as DataSource

  fun cleanup(): Nothing {
    events += "cleanup"
    throw cleanupFailure
  }

  fun containerStopped() {
    events += "container.stop"
  }
}
