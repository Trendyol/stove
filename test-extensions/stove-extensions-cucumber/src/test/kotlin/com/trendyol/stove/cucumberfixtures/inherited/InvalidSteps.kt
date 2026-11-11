package com.trendyol.stove.cucumberfixtures.inherited

import com.trendyol.stove.extensions.cucumber.Given

open class ParentSteps {
  @Given("a configured step")
  suspend fun step() = Unit
}

class InvalidSteps : ParentSteps()
