package com.trendyol.stove.testing.e2e.system

import com.trendyol.stove.testing.e2e.reporting.JsonReportRenderer
import com.trendyol.stove.testing.e2e.reporting.PrettyConsoleRenderer
import com.trendyol.stove.testing.e2e.reporting.ReportRenderer
import com.trendyol.stove.testing.e2e.system.abstractions.*

data class TestSystemOptions(
  val keepDependenciesRunning: Boolean = false,
  val stateStorageFactory: StateStorageFactory = StateStorageFactory.Default(),
  val runMigrationsAlways: Boolean = false,
  val reportingEnabled: Boolean = true,
  val dumpReportOnTestFailure: Boolean = true,
  val dumpReportOnStop: Boolean = false,
  val defaultRenderer: ReportRenderer = PrettyConsoleRenderer,
  val failureRenderer: ReportRenderer = PrettyConsoleRenderer,
  val fileRenderer: ReportRenderer = JsonReportRenderer,
  val reportToConsole: Boolean = true,
  val reportToFile: Boolean = false,
  val reportFilePath: String = "build/stove-reports"
) {
  inline fun <reified TState : ExposedConfiguration, reified TSystem : PluggedSystem> createStateStorage(): StateStorage<TState> =
    (this.stateStorageFactory(this, TSystem::class, TState::class))
}
