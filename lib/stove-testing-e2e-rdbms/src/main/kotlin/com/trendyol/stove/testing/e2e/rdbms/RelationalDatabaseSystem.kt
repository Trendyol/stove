package com.trendyol.stove.testing.e2e.rdbms

import com.trendyol.stove.testing.e2e.database.DatabaseSystem
import com.trendyol.stove.testing.e2e.system.TestSystem
import com.trendyol.stove.testing.e2e.system.abstractions.ExposesConfiguration
import com.trendyol.stove.testing.e2e.system.abstractions.RunAware
import io.r2dbc.spi.ConnectionFactory
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.runBlocking
import kotlin.reflect.KClass

@Suppress("UNCHECKED_CAST", "MemberVisibilityCanBePrivate")
abstract class RelationalDatabaseSystem<SELF : RelationalDatabaseSystem<SELF>> protected constructor(
    override val testSystem: TestSystem,
    protected val context: RelationalDatabaseContext<*>,
) : DatabaseSystem, RunAware, ExposesConfiguration {
    private lateinit var sqlOperations: SqlOperations

    protected abstract val connectionFactory: ConnectionFactory

    override suspend fun run() {
        context.container.start()
        sqlOperations = SqlOperations(connectionFactory)
        sqlOperations.open()
    }

    override suspend fun stop() {
        sqlOperations.close()
        context.container
        context.container.stop()
    }

    override fun configuration(): List<String> {
        val exposedConfiguration = RelationalDatabaseExposedConfiguration(
            jdbcUrl = context.container.jdbcUrl,
            host = context.container.host,
            database = context.container.databaseName,
            port = context.container.firstMappedPort,
            password = context.container.password,
            username = context.container.username,
        )

        return context.configureExposedConfiguration(exposedConfiguration) + listOf(
            "database.jdbcUrl=${exposedConfiguration.jdbcUrl}",
            "database.host=${exposedConfiguration.host}",
            "database.database=${exposedConfiguration.database}",
            "database.port=${exposedConfiguration.port}",
            "database.password=${exposedConfiguration.password}",
            "database.username=${exposedConfiguration.username}",
        )
    }

    override fun close() = runBlocking {
        stop()
    }

    override suspend fun <T : Any> shouldQuery(
        query: String,
        assertion: (List<T>) -> Unit,
        clazz: KClass<T>,
    ): SELF {
        var results: List<T> = emptyList()
        sqlOperations.transaction {
            results = it.select(query).map { r, rm -> mapper(r, rm, clazz) }.asFlow().toList()
        }

        assertion(results)
        return this as SELF
    }

    suspend fun shouldExecute(
        sql: String,
    ): SELF {
        sqlOperations.transaction {
            it.execute(sql)
        }
        return this as SELF
    }

    companion object {
        suspend inline fun <reified T : Any> RelationalDatabaseSystem<*>.shouldQuery(
            id: String,
            noinline assertion: (List<T>) -> Unit,
        ): DatabaseSystem = this.shouldQuery(id, assertion, T::class)
    }
}
