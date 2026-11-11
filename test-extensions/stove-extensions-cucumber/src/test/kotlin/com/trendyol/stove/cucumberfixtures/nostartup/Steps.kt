package com.trendyol.stove.cucumberfixtures.nostartup

import com.trendyol.stove.extensions.cucumber.Given

class Steps {
  @Given("a configured step")
  suspend fun step(): Unit = error("A step body must not execute without Stove startup")
}
