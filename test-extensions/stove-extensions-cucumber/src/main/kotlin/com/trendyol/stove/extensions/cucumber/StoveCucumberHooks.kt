package com.trendyol.stove.extensions.cucumber

import io.cucumber.java.After
import io.cucumber.java.Before
import io.cucumber.java.Scenario

/** Include com.trendyol.stove.extensions.cucumber in cucumber.glue for scenario reporting and failure attachments. */
class StoveCucumberHooks {
  @Before(order = Int.MIN_VALUE)
  fun startScenario() {
    StoveCucumberPlugin.current()
  }

  @After(order = Int.MIN_VALUE)
  fun attachFailure(scenario: Scenario) = StoveCucumberPlugin.current().attachFailureTo(scenario)
}
