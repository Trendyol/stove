package com.trendyol.stove.testing.e2e.database

import kotlin.reflect.KClass

interface DocumentDatabaseSystem : DatabaseSystem {

    /**
     * Finds the given [key] and returns the instance if exists, otherwise throws [Exception]
     * Caller-side needs to assert based on the list
     *
     * Also: [Companion.shouldGet]
     */
    suspend fun <T : Any> shouldGet(
        key: String,
        assertion: (T) -> Unit,
        clazz: KClass<T>,
    ): DocumentDatabaseSystem

    /**
     * Fails if the document exists with the given [key]
     */
    suspend fun shouldNotExist(
        key: String,
    ): DocumentDatabaseSystem

    /**
     * Deletes the given [key] from the database
     */
    suspend fun shouldDelete(key: String): DocumentDatabaseSystem

    /**
     * Saves the given [instance] to the database
     */
    suspend fun <T : Any> save(
        collection: String,
        id: String,
        instance: T,
    ): DocumentDatabaseSystem

    companion object {

        /**
         * Finds the given [id] and returns the instance if exists, otherwise throws [Exception]
         * Caller-side needs to assert based on the list
         *
         */
        suspend inline fun <reified T : Any> DocumentDatabaseSystem.shouldGet(
            id: String,
            noinline assertion: (T) -> Unit,
        ): DocumentDatabaseSystem = this.shouldGet(id, assertion, T::class)
    }
}
