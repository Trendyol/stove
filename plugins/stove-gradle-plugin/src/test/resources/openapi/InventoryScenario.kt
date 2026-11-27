package fixture

import com.fasterxml.jackson.annotation.JsonProperty
import com.trendyol.stove.serialization.StoveSerde
import com.trendyol.stove.system.abstractions.SystemKey
import com.trendyol.stove.system.stove
import com.trendyol.stove.wiremock.*
import stove.generated.wiremock.inventory.*
import java.math.BigDecimal
import java.net.URI
import java.net.http.*
import java.time.OffsetDateTime

object Inventory : SystemKey

enum class Channel { online, @JsonProperty("shop") IN_STORE }

suspend fun exerciseInventory(baseUrl: String) {
  val line = StockLine(sku = "tea", quantity = 2)
  val reservation = Reservation(id = "r1", lines = listOf(line))
  val cart = ReserveStock(lines = listOf(line), labels = mapOf("\$ref" to "cart-42"))
  val warehouse = "EU/West"
  val startsAt = OffsetDateTime.parse("2026-01-01T10:00:00Z")
  val weight = BigDecimal("0.0000001")
  val slot = Slot(primary = null, latest = line)
  stove {
    wiremock(Inventory) {
      inventory.reserveStock(warehouseId = warehouse, channel = Channel.online, idempotencyKey = "cart-42", body = cart) {
        created(reservation)
      }
      inventory.reserveStock(warehouseId = warehouse, channel = Channel.IN_STORE) {
        conflict(InventoryProblem(reason = "sold out"))
      }
      inventory.reservationState(warehouseId = warehouse, reservationId = "r1") { ok(ReservationState.held) }
      inventory.releaseStock(warehouseId = warehouse, reservationId = "r1") { noContent() }
      inventory.pickupSlot(warehouseId = warehouse, startsAt = startsAt, minWeight = weight, xUntil = startsAt) { ok(slot) }
      inventory.pickupSlot(warehouseId = warehouse, startsAt = startsAt.plusHours(1)) { ok(null) }
    }
  }

  val client = HttpClient.newHttpClient()
  val reservations = "$baseUrl/api/v2/warehouses/EU%2FWest/reservations"
  fun call(method: String, suffix: String, body: String? = null) = client.send(
    HttpRequest.newBuilder(URI(reservations + suffix))
      .header("Idempotency-Key", "cart-42")
      .header("Content-Type", "application/json")
      .method(method, body?.let(HttpRequest.BodyPublishers::ofString) ?: HttpRequest.BodyPublishers.noBody()).build(),
    HttpResponse.BodyHandlers.ofString(),
  )
  val payload = """{"labels":{"${'$'}ref":"cart-42"},"lines":[{"quantity":2,"sku":"tea"}]}"""
  val created = call("POST", "?channel=online", payload)
  val json = StoveSerde.jackson.default
  check(created.statusCode() == 201 && json.readValue(created.body(), Reservation::class.java) == reservation)
  check(call("POST", "?channel=online", payload.replace("tea", "coffee")).statusCode() == 404)
  val problem = call("POST", "?channel=shop")
  check(problem.statusCode() == 409 && problem.headers().firstValue("Content-Type").get() == "application/problem+json")
  check(json.readValue(problem.body(), InventoryProblem::class.java).reason == "sold out")
  check(json.readValue(call("GET", "/r1").body(), ReservationState::class.java) == ReservationState.held)
  check(call("DELETE", "/r1").statusCode() == 204)
  val slots = "$baseUrl/api/v2/warehouses/EU%2FWest/slots"
  fun get(suffix: String) = client.send(
    HttpRequest.newBuilder(URI(slots + suffix)).header("X-Until", "2026-01-01T10:00:00Z").build(),
    HttpResponse.BodyHandlers.ofString(),
  )
  val free = get("/2026-01-01T10:00:00Z?minWeight=0.0000001")
  check(free.statusCode() == 200 && json.readValue(free.body(), Slot::class.java) == slot) { free.toString() + free.body() }
  check(get("/2026-01-01T11:00:00Z").body() == "null")
  stove {
    wiremock(Inventory) {
      shouldHaveBeenCalled(inventory.requests.reserveStock(warehouseId = warehouse, channel = Channel.online, body = cart))
      shouldHaveBeenCalled(inventory.requests.releaseStock(warehouseId = warehouse, reservationId = "r1"))
    }
  }
}
