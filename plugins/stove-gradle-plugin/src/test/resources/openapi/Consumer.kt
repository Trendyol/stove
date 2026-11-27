package fixture

import com.github.tomakehurst.wiremock.client.WireMock.exactly
import com.trendyol.stove.system.*
import com.trendyol.stove.system.abstractions.*
import com.trendyol.stove.wiremock.*
import java.net.URI
import java.net.http.*
import kotlinx.coroutines.runBlocking
import stove.generated.wiremock.supplier.*
import stove.generated.wiremock.inventory.inventory

data class Item(val value: String)
object Supplier : SystemKey

fun main() = runBlocking {
  lateinit var supplierUrl: String
  lateinit var inventoryUrl: String
  Stove().with {
    wiremock(Supplier) { WireMockSystemOptions { supplierUrl = it.baseUrl; emptyList() } }
    wiremock(Inventory) { WireMockSystemOptions { inventoryUrl = it.baseUrl; emptyList() } }
    applicationUnderTest(object : ApplicationUnderTest<Unit> {
      override suspend fun start(configurations: List<String>) = Unit
      override suspend fun stop() = Unit
    })
  }.run()
  try {
    exerciseInventory(inventoryUrl)
    exerciseSupplier(supplierUrl)
  } finally { Stove.stop() }
}

private suspend fun exerciseSupplier(supplierUrl: String) {
  val id = "a@b:c/d e"
  stove {
    wiremock(Supplier) {
      check(runCatching { supplier.submit("empty") {} }.isFailure)
      check(runCatching { supplier.submit("twice") { noContent(); noContent() } }.isFailure)
      supplier.submit("any") { noContent() }
      supplier.submit(id, limit = 2, xMode = "draft", body = Item("x")) {
        conflict(SubmitResponse(detail = "busy"))
      }
    }
  }
  val client = HttpClient.newHttpClient()
  fun post(path: String) = client.send(HttpRequest.newBuilder(URI("$supplierUrl$path"))
    .header("X-Mode", "draft").POST(HttpRequest.BodyPublishers.ofString("""{"value":"x"}""")).build(),
    HttpResponse.BodyHandlers.ofString())
  check(post("/v1/orders/any?ignored=1").statusCode() == 204)
  for (segment in listOf("a@b:c%2Fd%20e", "a%40b%3Ac%2fd%20e")) {
    val problem = post("/v1/orders/$segment?limit=2")
    check(problem.statusCode() == 409 && problem.body() == """{"detail":"busy"}""") { problem.toString() + problem.body() }
    check(problem.headers().firstValue("Content-Type").get() == "application/problem+json")
  }
  check(post("/v1/orders/a@b:c/d%20e?limit=2").statusCode() == 404)
  stove {
    wiremock(Supplier) {
      shouldHaveBeenCalled(supplier.requests.submit(id, limit = 2, xMode = "draft", body = Item("x")), exactly(2))
      shouldNotHaveBeenCalled(supplier.requests.submit(id, limit = 1))
      shouldNotHaveBeenCalled(inventory.requests.releaseStock(warehouseId = "EU/West", reservationId = "r1"))
    }
  }
}
