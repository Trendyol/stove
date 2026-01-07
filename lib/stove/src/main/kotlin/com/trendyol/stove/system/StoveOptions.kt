package com.trendyol.stove.system

import com.trendyol.stove.reporting.JsonReportRenderer
import com.trendyol.stove.reporting.PrettyConsoleRenderer
import com.trendyol.stove.reporting.ReportRenderer
import com.trendyol.stove.system.abstractions.*

data class StoveOptions(
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
