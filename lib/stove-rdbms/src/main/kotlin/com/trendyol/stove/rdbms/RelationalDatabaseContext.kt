package com.trendyol.stove.rdbms

import com.trendyol.stove.system.abstractions.SystemRuntime

abstract class RelationalDatabaseContext(
  val runtime: SystemRuntime,
  val configureExposedConfiguration: (RelationalDatabaseExposedConfiguration) -> List<String>
)
