package com.trendyol.stove.testing.e2e.rdbms.mssql

import com.trendyol.stove.testing.e2e.rdbms.*
import com.trendyol.stove.testing.e2e.system.annotations.StoveDsl

@StoveDsl
class MsSqlContext(
  container: StoveMsSqlContainer,
  val options: MsSqlOptions
) : RelationalDatabaseContext<StoveMsSqlContainer>(container, options.configureExposedConfiguration)

@StoveDsl
data class SqlMigrationContext(
  val options: MsSqlOptions,
  val operations: SqlOperations,
  val executeAsRoot: suspend (String) -> Unit
)
