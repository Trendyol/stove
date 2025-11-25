package com.trendyol.stove.testing.e2e.rdbms

import com.trendyol.stove.testing.e2e.system.abstractions.SystemRuntime

abstract class RelationalDatabaseContext(
  val runtime: SystemRuntime,
  val configureExposedConfiguration: (RelationalDatabaseExposedConfiguration) -> List<String>
)
