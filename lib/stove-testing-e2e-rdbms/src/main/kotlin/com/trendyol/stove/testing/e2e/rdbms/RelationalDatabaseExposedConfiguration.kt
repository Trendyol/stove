package com.trendyol.stove.testing.e2e.rdbms

data class RelationalDatabaseExposedConfiguration(
    val jdbcUrl: String,
    val host: String,
    val database: String,
    val port: Int,
    val password: String,
    val username: String,
)
