package com.trendyol.stove.testing.e2e.system.abstractions

/**
 * Marks the [TestSystem] as ready after it is started.
 * @author Oguzhan Soykan
 */
interface ReadyTestSystem {
    suspend fun run()
}
