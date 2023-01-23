package com.trendyol.stove.testing.e2e.database

import com.trendyol.stove.testing.e2e.system.abstractions.PluggedSystem
import kotlin.reflect.KClass

/**
 * Database abstraction for testing
 * @author Oguzhan Soykan
 */
interface DatabaseSystem : PluggedSystem {

    /**
     * Executes the given [query] and returns a list for [assertion]
     * Caller-side needs to assert based on the list
     *
     * Also: [Companion.shouldQuery]
     */
    suspend fun <T : Any> shouldQuery(
        query: String,
        assertion: (List<T>) -> Unit,
        clazz: KClass<T>,
    ): DatabaseSystem

    /**
     * Finds the given [id] and returns the instance if exists, otherwise throws [Exception]
     * Caller-side needs to assert based on the list
     *
     * Also: [Companion.shouldGet]
     */
    suspend fun <T : Any> shouldGet(
        id: String,
        assertion: (T) -> Unit,
        clazz: KClass<T>,
    ): DatabaseSystem

    /**
     * Deletes the given [id] from the database
     */
    suspend fun shouldDelete(id: String): DatabaseSystem

    companion object {

        /**
         * Executes the given [query] and returns a list for [assertion]
         * Caller-side needs to assert based on the list
         */
        suspend inline fun <reified T : Any> DatabaseSystem.shouldQuery(
            query: String,
            noinline assertion: (List<T>) -> Unit,
        ): DatabaseSystem = this.shouldQuery(query, assertion, T::class)

        /**
         * Finds the given [id] and returns the instance if exists, otherwise throws [Exception]
         * Caller-side needs to assert based on the list
         *
         */
        suspend inline fun <reified T : Any> DatabaseSystem.shouldGet(
            id: String,
            noinline assertion: (T) -> Unit,
        ): DatabaseSystem = this.shouldGet(id, assertion, T::class)
    }
}
