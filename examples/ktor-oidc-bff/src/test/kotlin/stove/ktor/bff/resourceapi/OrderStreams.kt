package stove.ktor.bff.resourceapi

import io.ktor.http.ContentType
import io.ktor.server.auth.principal
import io.ktor.server.request.header
import io.ktor.server.response.respondTextWriter
import io.ktor.server.routing.*
import io.ktor.server.websocket.webSocketRaw
import io.ktor.websocket.*
import stove.ktor.oidc.server.VerifiedAccessToken

/** These routes inherit the resource API's token, proof and scope authentication. */
fun Route.orderStreams() {
  get("/order-events") {
    val subject = checkNotNull(call.principal<VerifiedAccessToken>("access")).subject
    call.respondTextWriter(ContentType.Text.EventStream) {
      write("id: ${call.request.header("Last-Event-ID") ?: "order-1001"}\ndata: $subject\n\n")
      flush()
    }
  }
  webSocketRaw("/order-socket", protocol = "orders.v1") {
    val subject = checkNotNull(call.principal<VerifiedAccessToken>("access")).subject
    send(Frame.Text(subject))
    for (frame in incoming) {
      send(frame)
      flush()
      if (frame is Frame.Close) return@webSocketRaw
    }
  }
}
