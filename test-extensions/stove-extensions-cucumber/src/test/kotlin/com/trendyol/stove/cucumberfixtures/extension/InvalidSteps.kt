package com.trendyol.stove.cucumberfixtures.extension

import com.trendyol.stove.extensions.cucumber.Given

class InvalidSteps {
  @Given("a configured step")
  suspend fun String.step() = Unit
}
