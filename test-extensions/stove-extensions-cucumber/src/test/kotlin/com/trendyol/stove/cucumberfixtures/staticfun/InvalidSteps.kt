package com.trendyol.stove.cucumberfixtures.staticfun

import com.trendyol.stove.extensions.cucumber.Given

object InvalidSteps {
  @JvmStatic
  @Given("a configured step")
  suspend fun step() = Unit
}
