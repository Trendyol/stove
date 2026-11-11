package com.trendyol.stove.cucumberfixtures.nativeannotation

class InvalidSteps {
  @io.cucumber.java.en.Given("a configured step")
  suspend fun step() = Unit
}
