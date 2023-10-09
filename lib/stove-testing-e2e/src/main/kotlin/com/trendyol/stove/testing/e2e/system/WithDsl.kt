package com.trendyol.stove.testing.e2e.system

import com.trendyol.stove.testing.e2e.system.abstractions.ApplicationUnderTest
import com.trendyol.stove.testing.e2e.system.abstractions.PluggedSystem

/** The wrapper class for constructing the entire system with [PluggedSystem]s*/
@JvmInline
value class WithDsl(val testSystem: TestSystem) {
    fun applicationUnderTest(applicationUnderTest: ApplicationUnderTest<*>) {
        this.testSystem.applicationUnderTest(applicationUnderTest)
    }
}
