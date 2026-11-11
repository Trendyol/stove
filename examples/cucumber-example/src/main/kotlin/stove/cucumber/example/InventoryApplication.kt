package stove.cucumber.example

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.routing
import java.util.concurrent.ConcurrentHashMap

/** Small real HTTP application for demonstrating feature-driven tests without external services. */
fun startInventoryApplication(port: Int): Application {
  val stock = ConcurrentHashMap<String, Int>()
  val server = embeddedServer(CIO, host = "127.0.0.1", port = port) {
    routing {
      put("/inventory/{sku}") {
        val quantity = call.receiveText().toIntOrNull()
        if (quantity == null || quantity < 0) {
          call.respond(HttpStatusCode.BadRequest)
        } else {
          stock[call.parameters["sku"]!!] = quantity
          call.respond(HttpStatusCode.NoContent)
        }
      }
      post("/inventory/{sku}/reserve") {
        val quantity = call.receiveText().toIntOrNull()
        if (quantity == null || quantity <= 0) {
          call.respond(HttpStatusCode.BadRequest)
        } else {
          var reserved = false
          stock.computeIfPresent(call.parameters["sku"]!!) { _, available ->
            if (available >= quantity) {
              reserved = true
              available - quantity
            } else {
              available
            }
          }
          call.respond(if (reserved) HttpStatusCode.NoContent else HttpStatusCode.Conflict)
        }
      }
      get("/inventory/{sku}") {
        val quantity = stock[call.parameters["sku"]!!]
        if (quantity == null) call.respond(HttpStatusCode.NotFound) else call.respondText(quantity.toString())
      }
    }
  }
  server.start(wait = false)
  return server.application
}
