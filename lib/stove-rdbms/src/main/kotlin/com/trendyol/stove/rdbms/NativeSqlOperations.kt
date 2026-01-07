package com.trendyol.stove.rdbms

import kotliquery.*

class NativeSqlOperations(
  private val session: Session
) : AutoCloseable {
  fun execute(
    sql: String,
    vararg parameters: Parameter<*> = emptyArray()
  ): Int = session
    .run(queryOf(sql, *parameters).asUpdate)

  fun <T> select(
    sql: String,
    rowMapper: (Row) -> T
  ): List<T> = session
    .run(queryOf(sql).map(rowMapper).asList)

  override fun close() {
    session.close()
  }
}
