package com.trendyol.stove.testing.e2e.system

class TestSystemOptionsDsl {

    internal var options = TestSystemOptions()
    fun keepDependenciesRunning(): TestSystemOptionsDsl {
        options = options.copy(keepDependenciesRunning = true)
        return this
    }
}
