package com.trendyol.stove.testing.e2e.system.abstractions

import com.trendyol.stove.testing.e2e.system.TestSystem

/**
 * Represents the state of the [TestSystem] which is being captured.
 * @param TState the type of the state
 * @param state the state of the [TestSystem]
 * @param processId the process id of the [TestSystem]
 */
data class StateWithProcess<TState : Any>(
  val state: TState,
  val processId: Long
)
