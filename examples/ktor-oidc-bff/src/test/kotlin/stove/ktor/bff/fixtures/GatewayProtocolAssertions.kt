package stove.ktor.bff.fixtures

import io.kotest.matchers.shouldBe
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.utils.io.readLineStrict
import io.ktor.websocket.*
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withTimeout
import stove.ktor.bff.StoveConfig

suspend fun GatewayBrowser.shouldReceiveAuthenticatedEvents() = withTimeout(5_000) {
  HttpClient(CIO).use { client ->
    client.prepareGet("${StoveConfig.origin}/api/order-events") {
      this@shouldReceiveAuthenticatedEvents.headers.forEach { (name, value) -> header(name, value) }
      header("Last-Event-ID", "resume")
    }.execute { response ->
      response.status shouldBe HttpStatusCode.OK
      val events = response.bodyAsChannel()
      events.readLineStrict() shouldBe "id: resume"
      events.readLineStrict() shouldBe "data: $subject"
    }
  }
}

suspend fun GatewayBrowser.shouldUseAuthenticatedWebSocket(logout: suspend () -> Unit) = withTimeout(5_000) {
  HttpClient(CIO) { install(WebSockets) }.use { client ->
    client.prepareGet(StoveConfig.origin.replace("http:", "ws:") + "/api/order-socket") {
      this@shouldUseAuthenticatedWebSocket.headers.forEach { (name, value) -> header(name, value) }
      header(HttpHeaders.SecWebSocketProtocol, "orders.v1")
    }.execute { response ->
      response.status shouldBe HttpStatusCode.SwitchingProtocols
      response.headers[HttpHeaders.SecWebSocketProtocol] shouldBe "orders.v1"
      val session = response.body<ClientWebSocketSession>()
      try {
        (session.incoming.receive() as Frame.Text).readText() shouldBe subject
        session.send(Frame.Text("confirmed"))
        (session.incoming.receive() as Frame.Text).readText() shouldBe "confirmed"
        logout()
        checkNotNull((session.incoming.receive() as Frame.Close).readReason()).code shouldBe 1008.toShort()
      } finally {
        session.cancel()
      }
    }
  }
}
