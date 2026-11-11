package com.trendyol.stove.cucumberfixtures.privatefun

import com.trendyol.stove.extensions.cucumber.Given

class InvalidSteps {
  @Given("a configured step")
  private suspend fun step() = Unit
}
