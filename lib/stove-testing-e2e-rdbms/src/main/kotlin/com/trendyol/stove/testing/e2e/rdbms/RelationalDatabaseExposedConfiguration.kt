package com.trendyol.stove.testing.e2e.rdbms

import com.trendyol.stove.testing.e2e.system.abstractions.ExposedConfiguration

data class RelationalDatabaseExposedConfiguration(
  val jdbcUrl: String,
  val host: String,
  val port: Int,
  val password: String,
  val username: String
) : ExposedConfiguration
