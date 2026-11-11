package com.trendyol.stove.cucumberfixtures.probe

import com.trendyol.stove.extensions.cucumber.Given
import com.trendyol.stove.extensions.cucumber.Then
import com.trendyol.stove.system.StoveContext
import io.cucumber.java.ParameterType
import io.cucumber.java.Transpose
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import io.cucumber.java.en.Given as JavaGiven

class SuspendingSteps {
  private var ready = false

  enum class Colour { RED, BLUE }

  @ParameterType("red|blue")
  fun colour(value: String): Colour = Colour.valueOf(value.uppercase())

  @JavaGiven("a synchronous Java-backend step")
  fun nativeStep() {
    ready = true
  }

  @Then("a suspend step receives {int}, {string} and {colour}")
  suspend fun arguments(number: Int, text: String, colour: Colour) {
    withContext(Dispatchers.Default) {
      delay(1)
      ready shouldBe true
      number shouldBe 42
      text shouldBe "hello"
      colour shouldBe Colour.BLUE
      (currentCoroutineContext()[StoveContext] != null) shouldBe true
    }
  }

  @Then("a suspend step receives a table")
  suspend fun table(@Transpose rows: List<List<String>>) {
    delay(1)
    rows shouldBe listOf(listOf("a", "c"), listOf("b", "d"))
  }

  @Then("a suspend step receives a docstring")
  suspend fun docstring(text: String) {
    delay(1)
    text shouldBe "hello\nworld"
  }

  @Given("a suspend child fails")
  suspend fun childFails(): Unit = coroutineScope {
    launch {
      delay(1)
      throw AssertionError("suspend child failed")
    }
  }

  @Given("a suspend step is cancelled")
  suspend fun cancelled() {
    delay(1)
    throw CancellationException("suspend step cancelled")
  }
}
