package com.trendyol.stove.testing.e2e.system

import com.trendyol.stove.testing.e2e.system.abstractions.PluggedSystem
import com.trendyol.stove.testing.e2e.system.annotations.StoveDsl

/** The wrapper class for DSLs that are used write validations against the [PluggedSystem]*/
@JvmInline
@StoveDsl
value class ValidationDsl(
  val testSystem: TestSystem
)
