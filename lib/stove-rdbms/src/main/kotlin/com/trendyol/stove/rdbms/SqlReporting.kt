package com.trendyol.stove.rdbms

import arrow.core.Some
import com.trendyol.stove.reporting.Reports

/**
 * Runs a SQL query and its assertion within one report entry.
 * The query callback keeps connection access and row mapping inside the failure-reporting boundary.
 */
suspend fun <T> Reports.reportSqlQuery(
  sql: String,
  query: () -> List<T>,
  assertion: (List<T>) -> Unit
): List<T> = report(
  action = "Query",
  input = Some(sql.trim()),
  metadata = mapOf("sql" to sql.trim())
) {
  val results = query()
  assertion(results)
  results
}

/** Runs a SQL update and reports its affected-row count. */
suspend fun Reports.reportSqlExecution(
  sql: String,
  execute: () -> Int
): String = report(
  action = "Execute SQL",
  input = Some(sql.trim()),
  metadata = mapOf("sql" to sql.trim())
) {
  val affectedRows = execute()
  check(affectedRows >= 0) { "Failed to execute sql: $sql" }
  "$affectedRows row(s) affected"
}
