package com.trendyol.stove.extensions.cucumber

import com.trendyol.stove.extensions.StoveTestSession
import io.cucumber.java.Scenario
import io.cucumber.plugin.event.Result
import io.cucumber.plugin.event.Status

internal class CucumberScenarioSession(val session: StoveTestSession) : AutoCloseable by session {
  private var failure: String? = null
  private var attachedFailure: String? = null

  fun record(result: Result) {
    if (result.status == Status.PASSED || result.status == Status.SKIPPED) return
    val message = result.error?.let(StoveTestSession::failureMessage) ?: result.status.name
    if (failure == message) return
    failure = message
    StoveTestSession.reportFailure(message)
  }

  fun attachFailureTo(scenario: Scenario) {
    if (failure == null) return
    scenario.attach(diagnostics(), "text/plain", "Stove diagnostics")
    attachedFailure = failure
  }

  fun finish(result: Result) {
    // Cucumber's aggregate result can carry an earlier failure than the last after hook.
    // Step events have already reported those failures; do not replace the newest one.
    if (failure == null) record(result)
    // A final hook may fail after the attachment hook has run. Preserve its diagnostics too.
    if (failure != attachedFailure) System.err.println(diagnostics())
  }

  private fun diagnostics(): String = listOfNotNull(
    "${session.context.testName} (${session.context.testId})",
    failure,
    StoveTestSession.failureReport().takeIf { it.isNotBlank() }
  ).joinToString("\n\n").replace(ANSI_COLORS, "")

  private companion object {
    val ANSI_COLORS = Regex("\u001B\\[[0-9;]*m")
  }
}
