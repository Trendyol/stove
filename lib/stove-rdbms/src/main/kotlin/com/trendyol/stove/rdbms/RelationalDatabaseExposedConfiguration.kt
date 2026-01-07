package com.trendyol.stove.rdbms

import com.trendyol.stove.system.abstractions.ExposedConfiguration

data class RelationalDatabaseExposedConfiguration(
  val jdbcUrl: String,
  val host: String,
  val port: Int,
  val password: String,
  val username: String
) : ExposedConfiguration
