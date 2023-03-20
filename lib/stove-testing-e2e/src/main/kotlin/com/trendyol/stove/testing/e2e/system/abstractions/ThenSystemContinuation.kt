package com.trendyol.stove.testing.e2e.system.abstractions

import com.trendyol.stove.testing.e2e.system.TestSystem

/**
 * @author Oguzhan Soykan
 */
interface ThenSystemContinuation {
    val testSystem: TestSystem

    fun then(): TestSystem = testSystem

    suspend fun executeWithReuseCheck(action: suspend () -> Unit) {
        if (testSystem.options.keepDependenciesRunning) {
            return
        }
        action()
    }
}
