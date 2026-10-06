package stove.ktor.bff.resourceapi

import com.nimbusds.jwt.SignedJWT
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

class ProtocolApiState {
  val releaseEvents = CompletableDeferred<Unit>()
  val eventStreamClosed = CompletableDeferred<Unit>()
  val socketClosed = CompletableDeferred<Unit>()
  val requests = AtomicInteger()
  val invalidations = AtomicInteger()
  val proofs = ConcurrentLinkedQueue<SignedJWT>()
}

fun Application.gatewayProtocolApi(state: ProtocolApiState) {
  install(WebSockets) {
    channels {
      incoming = bounded(8)
      outgoing = bounded(8)
    }
  }
  intercept(ApplicationCallPipeline.Call) { state.requests.incrementAndGet() }
  routing {
    get("/events/live") {
      call.respondTextWriter(ContentType.Text.EventStream) {
        write(": heartbeat\nid: ${call.request.headers["Last-Event-ID"] ?: "first"}\nretry: 1000\ndata: hello\n\n")
        flush()
        state.releaseEvents.await()
        write("data: goodbye\n\n")
      }
    }
    get("/events/active") {
      call.respondTextWriter(ContentType.Text.EventStream) {
        repeat(8) {
          write("data: $it\n\n")
          flush()
          delay(75)
        }
      }
    }
    get("/events/idle") {
      call.respondTextWriter(ContentType.Text.EventStream) {
        try {
          write(": open\n\n")
          flush()
          state.releaseEvents.await()
        } finally {
          state.eventStreamClosed.complete(Unit)
        }
      }
    }
    get("/events/disconnect") {
      call.respondTextWriter(ContentType.Text.EventStream) {
        try {
          while (currentCoroutineContext().isActive) {
            write("data: ${"x".repeat(8192)}\n\n")
            flush()
            delay(20)
          }
        } finally {
          state.eventStreamClosed.complete(Unit)
        }
      }
    }
    get("/sockets/nonce") {
      val proof = SignedJWT.parse(call.request.headers["DPoP"])
      state.proofs.add(proof)
      if (proof.jwtClaimsSet.getStringClaim("nonce") != "required") {
        call.response.header("DPoP-Nonce", "required")
        call.respondText("{\"error\":\"use_dpop_nonce\"}", ContentType.Application.Json, HttpStatusCode.Unauthorized)
      } else {
        call.respond(WebSocketUpgrade(call) { echoFrames(state) })
      }
    }
    get("/sockets/unauthorized") { call.respond(HttpStatusCode.Unauthorized) }
    get("/events/end") { call.respond(HttpStatusCode.NoContent) }
    get("/events/invalid") { call.respondText("not an event stream") }
    get("/events/slow") {
      delay(2_000)
      call.respond(HttpStatusCode.NoContent)
    }
    get("/sockets/forbidden") { call.respondText("upstream policy", status = HttpStatusCode.Forbidden) }
    get("/sockets/redirect") { call.respondRedirect("/sockets/echo") }
    get("/sockets/slow") {
      delay(2_000)
      call.respond(HttpStatusCode.Forbidden)
    }
    get("/sockets/bad-accept") {
      call.response.header(HttpHeaders.SecWebSocketAccept, "invalid")
      call.respond(WebSocketUpgrade(call) { awaitCancellation() })
    }
    get("/sockets/extensions") {
      call.response.header(HttpHeaders.SecWebSocketExtensions, "permessage-deflate")
      call.respond(WebSocketUpgrade(call) { awaitCancellation() })
    }
    get("/sockets/wrong-protocol") { call.respond(WebSocketUpgrade(call, "unoffered") { awaitCancellation() }) }
    webSocketRaw("/sockets/echo") { echoFrames(state) }
    webSocketRaw("/sockets/protocol", protocol = "orders.v1") { echoFrames(state) }
    webSocketRaw("/sockets/stall") { state.releaseEvents.await() }
    webSocketRaw("/sockets/push") {
      repeat(6) {
        send(Frame.Text("$it"))
        flush()
        delay(75)
      }
      send(Frame.Close(CloseReason(CloseReason.Codes.NORMAL, "done")))
      flush()
    }
    webSocketRaw("/sockets/server-close") {
      send(Frame.Close(CloseReason(4001, "finished")))
      flush()
      for (frame in incoming) if (frame is Frame.Close) break
    }
    webSocketRaw("/sockets/idle") {
      try {
        for (frame in incoming) if (frame is Frame.Close) break
      } finally {
        state.socketClosed.complete(Unit)
      }
    }
  }
}

private suspend fun WebSocketSession.echoFrames(state: ProtocolApiState) {
  try {
    for (frame in incoming) {
      send(frame)
      flush()
      if (frame is Frame.Close) return
    }
  } finally {
    state.socketClosed.complete(Unit)
  }
}
