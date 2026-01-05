package com.trendyol.stove.testing.e2e.system

import com.trendyol.stove.testing.e2e.reporting.ReportRenderer
import com.trendyol.stove.testing.e2e.system.abstractions.StateStorageFactory
import com.trendyol.stove.testing.e2e.system.annotations.StoveDsl
import org.slf4j.LoggerFactory

/**
 * DSL for configuring [TestSystemOptions].
 *
 * Example:
 * ```kotlin
 * TestSystem {
 *   if (isRunningLocally()) {
 *     enableReuseForTestContainers()
 *     keepDependenciesRunning()
 *   }
 *   reporting {
 *     enabled()
 *     dumpOnFailure()
 *   }
 * }
 * ```
 */
@StoveDsl
class TestSystemOptionsDsl {
  private val logger = LoggerFactory.getLogger(javaClass)
  private val propertiesFile = PropertiesFile()

  internal var options = TestSystemOptions()
    private set

  // ═══════════════════════════════════════════════════════════════════════════
  // Container & Environment
  // ═══════════════════════════════════════════════════════════════════════════

  /**
   * Keep dependencies (containers) running after tests complete.
   * Requires `.testcontainers.properties` file - call [enableReuseForTestContainers] first.
   */
  fun keepDependenciesRunning(): TestSystemOptionsDsl = apply {
    logger.info(
      """
      |You have chosen to keep dependencies running.
      |For that Stove needs '.testcontainers.properties' file under your user(~/).
      |To add that call 'enableReuseForTestContainers()' method
      """.trimMargin()
    )
    propertiesFile.detectAndLogStatus()
    options = options.copy(keepDependenciesRunning = true)
  }

  /**
   * Check if tests are running locally (not on CI).
   */
  fun isRunningLocally(): Boolean = !isRunningOnCI()

  /**
   * Enable container reuse in TestContainers by creating the required properties file.
   */
  fun enableReuseForTestContainers(): Unit = propertiesFile.enable()

  /**
   * Configure custom state storage factory.
   */
  fun stateStorage(factory: StateStorageFactory): TestSystemOptionsDsl = apply {
    options = options.copy(stateStorageFactory = factory)
  }

  /**
   * Always run migrations, even if the database state hasn't changed.
   */
  fun runMigrationsAlways(): TestSystemOptionsDsl = apply {
    options = options.copy(runMigrationsAlways = true)
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // Reporting Configuration
  // ═══════════════════════════════════════════════════════════════════════════

  /**
   * Configure reporting options using the [ReportingDsl].
   *
   * Example:
   * ```kotlin
   * reporting {
   *   enabled()
   *   dumpOnFailure()
   * }
   * ```
   */
  fun reporting(configure: ReportingDsl.() -> Unit): TestSystemOptionsDsl = apply {
    ReportingDsl(this).configure()
  }

  /** Enable reporting. */
  fun reportingEnabled(enabled: Boolean = true): TestSystemOptionsDsl = apply {
    options = options.copy(reportingEnabled = enabled)
  }

  /** Dump report on test failure. */
  fun dumpReportOnTestFailure(enabled: Boolean = true): TestSystemOptionsDsl = apply {
    options = options.copy(dumpReportOnTestFailure = enabled)
  }

  /** Set the renderer used for test failure reports. */
  fun failureRenderer(renderer: ReportRenderer): TestSystemOptionsDsl = apply {
    options = options.copy(failureRenderer = renderer)
  }

  private fun isRunningOnCI(): Boolean = CI_ENV_VARS.any { System.getenv(it) == "true" }

  companion object {
    private val CI_ENV_VARS = listOf("CI", "GITLAB_CI", "GITHUB_ACTIONS")
  }
}

/**
 * DSL for configuring reporting options in a grouped, fluent manner.
 */
@StoveDsl
class ReportingDsl(
  private val parent: TestSystemOptionsDsl
) {
  /** Enable reporting. */
  fun enabled(value: Boolean = true) = parent.reportingEnabled(value)

  /** Disable reporting. */
  fun disabled() = enabled(false)

  /** Dump report on test failure. */
  fun dumpOnFailure(value: Boolean = true) = parent.dumpReportOnTestFailure(value)

  /** Set the failure renderer. */
  fun failureRenderer(renderer: ReportRenderer) = parent.failureRenderer(renderer)
}
