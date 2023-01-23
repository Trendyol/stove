package com.trendyol.stove.testing.e2e.system.abstractions

/**
 * @author Oguzhan Soykan
 */
interface PluggedSystem : AutoCloseable, ThenSystemContinuation

/**
 * @author Oguzhan Soykan
 */
interface CleansUp {

    suspend fun cleanup(): PluggedSystem
}
