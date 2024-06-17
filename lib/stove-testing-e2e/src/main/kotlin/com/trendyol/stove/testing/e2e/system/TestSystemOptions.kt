package com.trendyol.stove.testing.e2e.system

import com.trendyol.stove.testing.e2e.system.abstractions.*

data class TestSystemOptions(
  val keepDependenciesRunning: Boolean = false,
  val stateStorageFactory: StateStorageFactory = StateStorageFactory.Default(),
  val runMigrationsAlways: Boolean = false
) {
  inline fun <reified TState : ExposedConfiguration, reified TSystem : PluggedSystem> createStateStorage(): StateStorage<TState> =
    (this.stateStorageFactory(this, TSystem::class, TState::class))
}
