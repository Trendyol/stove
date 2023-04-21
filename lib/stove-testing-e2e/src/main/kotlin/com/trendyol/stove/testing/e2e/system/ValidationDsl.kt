package com.trendyol.stove.testing.e2e.system

import com.trendyol.stove.testing.e2e.system.abstractions.PluggedSystem

/** The wrapper class for DSLs that are used write validations against the [PluggedSystem]*/
@JvmInline
value class ValidationDsl(val testSystem: TestSystem)
