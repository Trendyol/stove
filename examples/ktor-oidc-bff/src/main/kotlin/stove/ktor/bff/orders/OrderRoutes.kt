package stove.ktor.bff.orders

import io.ktor.http.*
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.*
import io.ktor.server.response.respondText
import io.ktor.server.routing.*
import kotlinx.serialization.json.*
import stove.ktor.gateway.*

/** Application-owned handlers coexist with the configured proxy subtree. */
fun Route.orderRoutes() {
  route("/api/orders") {
    get("/summary") { call.orderSummary() }
    post("/{id}/confirm") { call.confirmOrder() }
  }
}

private suspend fun ApplicationCall.orderSummary() {
  val status = request.queryParameters["status"].orEmpty()
  if (status !in setOf("", "confirmed", "pending")) throw GatewayFailure(HttpStatusCode.BadRequest, "Unsupported order status")
  gatewayRequest("/api/orders") { upstream ->
    if (upstream.status != HttpStatusCode.OK) return@gatewayRequest respondGateway(upstream)
    val bytes = upstream.body.readBytes(maxBytes = 64 * 1024)
    respondText(orderSummary(bytes, status).toString(), ContentType.Application.Json)
  }
}

private fun orderSummary(body: ByteArray, status: String): JsonObject {
  val payload = Json.parseToJsonElement(body.decodeToString()).jsonObject
  val orders = payload.getValue("orders").jsonArray
  val selected = orders.filter { status.isEmpty() || it.jsonObject.getValue("status").jsonPrimitive.content == status }
  return buildJsonObject {
    put("subject", payload.getValue("subject"))
    put("total", selected.size)
    putJsonArray("orderIds") { selected.forEach { add(it.jsonObject.getValue("id")) } }
  }
}

private suspend fun ApplicationCall.confirmOrder() {
  val id = parameters["id"].orEmpty()
  if (!id.matches(Regex("[A-Za-z0-9-]+"))) throw GatewayFailure(HttpStatusCode.BadRequest, "Invalid order ID")
  gatewayRequest("/orders/$id/confirmation", HttpMethod.Patch, configure = {
    textBody("""{"status":"confirmed"}""", ContentType.Application.Json)
  }) { upstream ->
    respondGateway(upstream)
  }
}
