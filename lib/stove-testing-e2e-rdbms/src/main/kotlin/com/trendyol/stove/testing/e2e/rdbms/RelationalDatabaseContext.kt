package com.trendyol.stove.testing.e2e.rdbms

import org.testcontainers.containers.JdbcDatabaseContainer

abstract class RelationalDatabaseContext<TContainer : JdbcDatabaseContainer<*>>(
    val container: TContainer,
    val configureExposedConfiguration: (RelationalDatabaseExposedConfiguration) -> List<String>,
)
