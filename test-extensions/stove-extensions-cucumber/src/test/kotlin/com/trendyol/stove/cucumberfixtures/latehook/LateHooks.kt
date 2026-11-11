package com.trendyol.stove.cucumberfixtures.latehook

import com.trendyol.stove.extensions.cucumber.StoveCucumberHooks
import io.cucumber.java.After
import io.cucumber.java.Scenario

class LateHooks {
  // Explicit ordering exercises a failure after attachment without relying on equal-order hooks.
  @After(order = 1)
  fun attach(scenario: Scenario) = StoveCucumberHooks().attachFailure(scenario)

  @After(order = 0)
  fun fail(): Unit = error("late teardown failure")
}
