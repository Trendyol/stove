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

    companion object {

        /**
         * Executes the given [query] and returns a list for [assertion]
         * Caller-side needs to assert based on the list
         */
        suspend inline fun <reified T : Any> DatabaseSystem.shouldQuery(
            query: String,
            noinline assertion: (List<T>) -> Unit,
        ): DatabaseSystem = this.shouldQuery(query, assertion, T::class)
    }
}
