package com.trendyol.stove.rdbms

import kotliquery.*

class NativeSqlOperations(
  private val session: Session
) : AutoCloseable {
  fun execute(
    sql: String,
    parameters: List<Parameter<*>> = emptyList()
  ): Int = session
    .run(queryOf(sql, *parameters.toTypedArray()).asUpdate)

  fun <T> select(
    sql: String,
    parameters: List<Parameter<*>> = emptyList(),
    rowMapper: (Row) -> T
  ): List<T> = session
    .run(queryOf(sql, *parameters.toTypedArray()).map(rowMapper).asList)

  override fun close() {
    session.close()
  }
}
