package com.trendyol.stove.testing.e2e.rdbms

import io.r2dbc.spi.Connection
import io.r2dbc.spi.ConnectionFactory
import io.r2dbc.spi.IsolationLevel
import io.r2dbc.spi.Row
import io.r2dbc.spi.RowMetadata
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.reactive.awaitFirst
import kotlinx.coroutines.reactive.awaitFirstOrNull

/**
 * An R2DBC abstraction that uses Kotlin coroutines.
 */
internal class SqlOperations(private val connectionFactory: ConnectionFactory) {

    private lateinit var connection: Connection

    /**
     * Opens a connection from the factory.
     */
    suspend fun open() {
        connection = this.connectionFactory.create().awaitFirst()
    }

    suspend fun close() {
        connection.close().awaitFirstOrNull()
    }

    /**
     * Open the connection within a transaction.
     */
    suspend fun <T> transaction(invoke: suspend (Handle) -> T): T {
        val handle = Handle(connection)
        connection.beginTransaction().awaitFirstOrNull()
        try {
            val ret = invoke(handle)
            connection.commitTransaction().awaitFirstOrNull()
            return ret
        } catch (ex: Exception) {
            connection.rollbackTransaction().awaitFirstOrNull()
            throw ex
        }
    }
}

/**
 * Wrapper for [Connection].
 */
internal class Handle(val connection: Connection) {

    /**
     * Change transaction isolation level.
     */
    fun transactionIsolationLevel(level: IsolationLevel) {
        require(connection.isAutoCommit)
        connection.transactionIsolationLevel = level
    }

    /**
     * Executes an update.
     * @param sql sql statement
     * @param parameters binding parameters
     * @return number of affected rows
     */
    suspend fun execute(
        sql: String,
        vararg parameters: Any
    ) {
        connection.createStatement(sql)
            .let {
                parameters.forEachIndexed { index, param -> it.bind(index, param) }
                it
            }.execute().awaitFirstOrNull()
    }

    /**
     * Creates a select query.
     * @param sql sql statement
     * @return raw r2dbc result
     */
    suspend fun select(
        sql: String
    ): io.r2dbc.spi.Result = connection.createStatement(sql).execute().awaitFirst()

    /**
     * Creates a select query.
     * @param sql sql statement
     * @param parameters binding parameters
     * @return query result
     */
    suspend fun select(
        sql: String,
        vararg parameters: Any
    ): Flow<Row> {
        val query = this.connection.createStatement(sql)

        parameters.forEachIndexed { index, param ->
            query.bind(index, param)
        }

        return query.execute().awaitFirst().map { t, _ -> t }.asFlow()
    }

    /**
     * Creates a select query that returns [T] given the [rowMapper] function.
     * @param sql sql statement
     * @param parameters binding parameters
     * @param rowMapper row mapping function
     * @return query result
     */
    suspend inline fun <reified T : Any> select(
        sql: String,
        noinline rowMapper: (row: Row, rowMetadata: RowMetadata) -> T,
        vararg parameters: Any
    ): Flow<T> {
        val query = this.connection.createStatement(sql)

        parameters.forEachIndexed { index, param ->
            query.bind(index, param)
        }

        return query.execute().awaitFirst().map(rowMapper).asFlow()
    }
}
