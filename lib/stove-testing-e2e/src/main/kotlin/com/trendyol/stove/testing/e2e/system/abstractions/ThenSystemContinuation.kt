package com.trendyol.stove.testing.e2e.system.abstractions

import com.trendyol.stove.testing.e2e.system.TestSystem

/**
 * Gives the ability to continue assertions and switch between components.
 * @author Oguzhan Soykan
 */
interface ThenSystemContinuation {
    val testSystem: TestSystem

    fun then(): TestSystem = testSystem

    /**
     * Executes the given action if the dependencies are not kept running.
     * @param action the action to be executed
     */
    suspend fun executeWithReuseCheck(action: suspend () -> Unit) {
        if (testSystem.options.keepDependenciesRunning) {
            return
        }
        action()
    }
}
