package com.trendyol.stove.testing.e2e.system

import com.trendyol.stove.testing.e2e.system.abstractions.*

data class TestSystemOptions(
  val keepDependenciesRunning: Boolean = false,
  val stateStorageFactory: StateStorageFactory = DefaultStateStorageFactory()
) {
  companion object {
    inline fun <reified TState : Any, reified TSystem : Any> TestSystemOptions.createStateStorage(): StateStorage<TState> =
      (this.stateStorageFactory(this, TSystem::class, TState::class))
  }
}
