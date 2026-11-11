package com.trendyol.stove.extensions.cucumber

import io.cucumber.java.After
import io.cucumber.java.Scenario

/** Include com.trendyol.stove.extensions.cucumber in cucumber.glue for failure attachments. */
class StoveCucumberHooks {
  @After(order = Int.MIN_VALUE)
  fun attachFailure(scenario: Scenario) = StoveCucumberPlugin.current().attachFailureTo(scenario)
}
