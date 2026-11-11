package com.trendyol.stove.extensions.cucumber

import com.trendyol.stove.extensions.StoveTestSession
import com.trendyol.stove.reporting.StoveTestContext
import com.trendyol.stove.system.Stove
import com.trendyol.stove.tracing.TraceContext
import io.cucumber.plugin.ConcurrentEventListener
import io.cucumber.plugin.event.EventPublisher
import io.cucumber.plugin.event.TestCase
import io.cucumber.plugin.event.TestCaseFinished
import io.cucumber.plugin.event.TestCaseStarted
import io.cucumber.plugin.event.TestStepFinished

/**
 * Scenario reporting for Cucumber. Register this plugin and the package's glue hooks.
 * Each Cucumber worker owns its scenario session; suspend steps propagate that session's context.
 */
class StoveCucumberPlugin : ConcurrentEventListener {
  override fun setEventPublisher(publisher: EventPublisher, isMultiThreaded: Boolean) {
    setEventPublisher(publisher)
  }

  override fun setEventPublisher(publisher: EventPublisher) {
    publisher.registerHandlerFor(TestCaseStarted::class.java, ::startScenario)
    publisher.registerHandlerFor(TestStepFinished::class.java) { opened()?.record(it.result) }
    publisher.registerHandlerFor(TestCaseFinished::class.java, ::finishScenario)
  }

  private fun startScenario(event: TestCaseStarted) {
    check(active.get() == null) { "A Stove Cucumber scenario is already active" }
    // Dry runs emit scenario events but never execute hooks or step bodies.
    active.set(
      lazy(LazyThreadSafetyMode.NONE) {
        check(Stove.instanceInitialized()) { "Start Stove in a Cucumber @BeforeAll hook before running scenarios" }
        CucumberScenarioSession(StoveTestSession.open(event.testCase.toStoveContext()))
      }
    )
  }

  private fun finishScenario(event: TestCaseFinished) {
    try {
      opened()?.use { scenario -> scenario.finish(event.result) }
    } finally {
      active.remove()
    }
  }

  private fun TestCase.toStoveContext(): StoveTestContext {
    val location = "$uri:${this.location.line}"
    return StoveTestContext(
      testId = TraceContext.sanitizeToAscii(location),
      testName = name,
      specName = uri.toString(),
      testPath = listOf(name, location)
    )
  }

  companion object {
    private val active = ThreadLocal<Lazy<CucumberScenarioSession>>()

    private fun opened(): CucumberScenarioSession? = active.get()?.takeIf { it.isInitialized() }?.value

    internal fun current(): CucumberScenarioSession = checkNotNull(active.get()) {
      "No Stove Cucumber scenario. Register com.trendyol.stove.extensions.cucumber.StoveCucumberPlugin"
    }.value
  }
}
