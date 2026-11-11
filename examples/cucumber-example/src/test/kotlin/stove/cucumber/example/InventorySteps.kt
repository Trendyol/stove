package stove.cucumber.example

import arrow.core.some
import com.trendyol.stove.extensions.cucumber.Given
import com.trendyol.stove.extensions.cucumber.Then
import com.trendyol.stove.extensions.cucumber.When
import com.trendyol.stove.http.http
import com.trendyol.stove.system.stove
import io.kotest.matchers.shouldBe
import java.util.UUID

/** PicoContainer supplies a new instance for each scenario, including each outline row. */
class InventoryScenario {
  val sku: String = UUID.randomUUID().toString()
  var responseStatus: Int? = null
}

class InventorySteps(private val scenario: InventoryScenario) {
  @Given("a product with {int} units in stock")
  suspend fun stock(quantity: Int) = stove {
    http {
      putAndExpectBodilessResponse("/inventory/${scenario.sku}", body = quantity.toString().some()) {
        it.status shouldBe 204
      }
    }
  }

  @When("I reserve {int} units")
  suspend fun reserve(quantity: Int) = stove {
    http {
      postAndExpectBodilessResponse("/inventory/${scenario.sku}/reserve", body = quantity.toString().some()) {
        scenario.responseStatus = it.status
      }
    }
  }

  @Then("the reservation response is {int}")
  suspend fun response(status: Int) {
    scenario.responseStatus shouldBe status
  }

  @Then("{int} units remain in stock")
  suspend fun remaining(quantity: Int) = stove {
    http {
      get<String>("/inventory/${scenario.sku}") {
        it shouldBe quantity.toString()
      }
    }
  }
}
