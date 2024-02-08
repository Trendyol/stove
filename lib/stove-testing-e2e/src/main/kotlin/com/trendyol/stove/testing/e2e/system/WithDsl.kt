package com.trendyol.stove.testing.e2e.system

import com.trendyol.stove.testing.e2e.system.abstractions.*
import com.trendyol.stove.testing.e2e.system.annotations.StoveDsl

/** The wrapper class for constructing the entire system with [PluggedSystem]s*/
@JvmInline
@StoveDsl
value class WithDsl(val testSystem: TestSystem) {
    @StoveDsl
    fun applicationUnderTest(applicationUnderTest: ApplicationUnderTest<*>) {
        this.testSystem.applicationUnderTest(applicationUnderTest)
    }
}
