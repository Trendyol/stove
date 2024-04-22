package com.trendyol.stove.testing.e2e.rdbms.mssql

import com.trendyol.stove.testing.e2e.rdbms.*
import com.trendyol.stove.testing.e2e.system.annotations.StoveDsl
import org.testcontainers.containers.MSSQLServerContainer

@StoveDsl
class MsSqlContext(
  container: MSSQLServerContainer<*>,
  val options: MsSqlOptions
) : RelationalDatabaseContext<MSSQLServerContainer<*>>(
    container,
    options.configureExposedConfiguration
  )

@StoveDsl
data class SqlMigrationContext(
  val options: MsSqlOptions,
  val operations: SqlOperations,
  val executeAsRoot: suspend (String) -> Unit
)
