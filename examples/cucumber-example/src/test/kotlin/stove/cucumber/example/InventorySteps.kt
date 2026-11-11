package stove.cucumber.example

import arrow.core.some
import com.trendyol.stove.extensions.cucumber.StoveSteps
import com.trendyol.stove.http.http
import io.kotest.matchers.shouldBe
import java.util.UUID

/** PicoContainer supplies a new instance for each scenario, including each outline row. */
class InventoryScenario {
  val sku: String = UUID.randomUUID().toString()
  var responseStatus: Int? = null
}

class InventorySteps(scenario: InventoryScenario) : StoveSteps({
  Given<Int>("a product with {int} units in stock") { quantity ->
    http {
      putAndExpectBodilessResponse("/inventory/${scenario.sku}", body = quantity.toString().some()) {
        it.status shouldBe 204
      }
    }
  }

  When<Int>("I reserve {int} units") { quantity ->
    http {
      postAndExpectBodilessResponse("/inventory/${scenario.sku}/reserve", body = quantity.toString().some()) {
        scenario.responseStatus = it.status
      }
    }
  }

  Then<Int>("the reservation response is {int}") { status ->
    scenario.responseStatus shouldBe status
  }

  And<Int>("{int} units remain in stock") { quantity ->
    http {
      get<String>("/inventory/${scenario.sku}") {
        it shouldBe quantity.toString()
      }
    }
  }
})
