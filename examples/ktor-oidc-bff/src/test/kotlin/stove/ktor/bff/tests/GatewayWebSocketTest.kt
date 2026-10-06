package stove.ktor.bff.tests

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.utils.io.*
import io.ktor.websocket.*
import kotlinx.coroutines.*
import kotlinx.io.readByteArray
import stove.ktor.bff.fixtures.*
import java.io.EOFException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds

class GatewayWebSocketTest : FunSpec({
  test("WebSocket backpressure stalls a producer and closes a peer that stops reading") {
    protocolGatewayTest {
      socket("/bulk/stall") {
        supervisorScope {
          val produced = AtomicInteger()
          val writer = async {
            val bytes = ByteArray(64 * 1024)
            repeat(4096) {
              send(Frame.Binary(true, bytes))
              produced.incrementAndGet()
            }
          }
          try {
            receiveCloseReason().code shouldBe 1001.toShort()
            (produced.get() < 4096) shouldBe true
          } finally {
            writer.cancel()
          }
        }
      }
    }
  }

  test("WebSocket close handshake has a deadline when the peer never acknowledges") {
    protocolGatewayTest {
      socket("/sockets/server-close") {
        (incoming.receive() as Frame.Close).readReason() shouldBe CloseReason(4001, "finished")
        withTimeout(1_000) { incoming.receiveCatching().isClosed shouldBe true }
      }
    }
  }

  test("WebSocket malformed handshakes fail before opening an upstream") {
    protocolGatewayTest {
      rawHandshake {
        removeAll { it.first == "Sec-WebSocket-Key" }
        add("Sec-WebSocket-Key" to "invalid")
      } shouldBe HttpStatusCode.BadRequest
      rawHandshake {
        removeAll { it.first == "Sec-WebSocket-Version" }
        add("Sec-WebSocket-Version" to "12")
      } shouldBe HttpStatusCode.UpgradeRequired
      rawHandshake { add("Origin" to origin) } shouldBe HttpStatusCode.Forbidden
      rawHandshake { add("Sec-WebSocket-Protocol" to "invalid protocol") } shouldBe HttpStatusCode.BadRequest
      rawHandshake(method = "POST") shouldBe HttpStatusCode.MethodNotAllowed
      rawHandshake(body = "x") { add("Content-Length" to "1") } shouldBe HttpStatusCode.BadRequest
      state.requests.get() shouldBe 0
    }
  }

  test("WebSocket refuses invalid upstream accept keys and unrequested extensions") {
    protocolGatewayTest {
      handshake("/sockets/bad-accept") shouldBe HttpStatusCode.BadGateway
      handshake("/sockets/extensions") shouldBe HttpStatusCode.BadGateway
    }
  }

  test("WebSocket rejects reserved close codes") {
    protocolGatewayTest {
      socket {
        send(Frame.Close(byteArrayOf(3, 237.toByte())))
        receiveCloseReason().code shouldBe 1002.toShort()
      }
    }
  }

  test("WebSocket DPoP nonce challenges retry a bodyless handshake with a new proof") {
    protocolGatewayTest(dpop = true) {
      socket("/sockets/nonce") {
        send(Frame.Text("authenticated"))
        (incoming.receive() as Frame.Text).readText() shouldBe "authenticated"
      }
      val proofs = state.proofs.toList()
      proofs.size shouldBe 2
      proofs[0].jwtClaimsSet.jwtid shouldNotBe proofs[1].jwtClaimsSet.jwtid
      proofs[1].jwtClaimsSet.getStringClaim("nonce") shouldBe "required"
      proofs[1].jwtClaimsSet.getStringClaim("htu").startsWith("http://") shouldBe true
      state.invalidations.get() shouldBe 0
      handshake("/sockets/unauthorized") shouldBe HttpStatusCode.Unauthorized
      state.invalidations.get() shouldBe 1
    }
  }

  test("gateway shutdown closes established WebSockets and their upstream") {
    protocolGatewayTest {
      socket("/sockets/idle") {
        coroutineScope {
          val stop = async(Dispatchers.IO) { stopGateway() }
          // Shutdown may close the transport before a close frame can be flushed.
          val frame = incoming.receiveCatching().getOrNull()
          (frame == null || frame is Frame.Close) shouldBe true
          stop.await()
        }
      }
      state.socketClosed.await()
    }
  }

  test("WebSocket traffic in one direction keeps an otherwise quiet connection open") {
    protocolGatewayTest(idle = 250.milliseconds) {
      socket("/sockets/push") {
        repeat(6) { (incoming.receive() as Frame.Text).readText() shouldBe "$it" }
      }
    }
  }

  test("WebSocket hooks can reject locally before the upgrade") {
    protocolGatewayTest(configure = {
      afterForward("/sockets/**") { reject(HttpStatusCode.Forbidden, "application policy") }
    }) {
      handshake("/sockets/echo") shouldBe HttpStatusCode.Forbidden
      state.socketClosed.await()
    }
  }

  test("WebSocket relays text, binary, fragmented UTF-8 and control frames without aggregation") {
    protocolGatewayTest {
      socket {
        send(Frame.Text("hello"))
        (incoming.receive() as Frame.Text).readText() shouldBe "hello"
        send(Frame.Binary(true, byteArrayOf(0, 1, 2)))
        incoming.receive().data shouldBe byteArrayOf(0, 1, 2)
        send(Frame.Text(false, byteArrayOf(0xe2.toByte())))
        val first = incoming.receive()
        first.fin shouldBe false
        first.data shouldBe byteArrayOf(0xe2.toByte())
        send(Frame.Ping(byteArrayOf(42)))
        val ping = incoming.receive()
        ping.frameType shouldBe FrameType.PING
        ping.data shouldBe byteArrayOf(42)
        send(Frame.Text(true, byteArrayOf(0x82.toByte(), 0xac.toByte())))
        incoming.receive().fin shouldBe true
        send(Frame.Pong(byteArrayOf(7)))
        incoming.receive().frameType shouldBe FrameType.PONG
        send(Frame.Close(CloseReason(4000, "complete")))
        (incoming.receive() as Frame.Close).readReason() shouldBe CloseReason(4000, "complete")
      }
      state.socketClosed.await()
    }
  }

  test("WebSocket negotiates an allowed subprotocol and invokes hooks once on the handshake") {
    val count = AtomicInteger()
    protocolGatewayTest(configure = {
      afterForward("/sockets/**") {
        count.incrementAndGet()
        response.headers["X-Hook"] = "socket"
      }
    }) {
      socket("/sockets/protocol") {
        call.response.headers[HttpHeaders.SecWebSocketProtocol] shouldBe "orders.v1"
        call.response.headers["X-Hook"] shouldBe "socket"
        send(Frame.Text("one"))
        incoming.receive()
        send(Frame.Text("two"))
        incoming.receive()
        count.get() shouldBe 1
      }
    }
  }

  test("WebSocket rejects missing and foreign origins before contacting upstream") {
    protocolGatewayTest {
      handshake("/sockets/echo", emptyMap()) shouldBe HttpStatusCode.Forbidden
      handshake("/sockets/echo", mapOf(HttpHeaders.Origin to "https://foreign.example")) shouldBe HttpStatusCode.Forbidden
      handshake("/required/protocol") shouldBe HttpStatusCode.BadRequest
      state.requests.get() shouldBe 0
    }
  }

  test("WebSocket rejects upstream errors and invalid negotiation before upgrading the browser") {
    protocolGatewayTest {
      handshake("/sockets/forbidden") shouldBe HttpStatusCode.Forbidden
      handshake("/sockets/redirect") shouldBe HttpStatusCode.BadGateway
      handshake("/sockets/slow") shouldBe HttpStatusCode.GatewayTimeout
      handshake("/sockets/wrong-protocol") shouldBe HttpStatusCode.BadGateway
    }
  }

  test("WebSocket preserves upstream close code and reason") {
    protocolGatewayTest {
      socket("/sockets/server-close") {
        val frame = incoming.receive() as Frame.Close
        frame.readReason() shouldBe CloseReason(4001, "finished")
        send(frame)
      }
    }
  }

  test("WebSocket bounds frame and fragmented message sizes") {
    protocolGatewayTest {
      socket {
        send(Frame.Binary(true, ByteArray(65)))
        receiveCloseReason().code shouldBe 1009.toShort()
      }
      socket {
        send(Frame.Binary(false, ByteArray(64)))
        incoming.receive().fin shouldBe false
        send(Frame.Binary(true, ByteArray(40)))
        receiveCloseReason().code shouldBe 1009.toShort()
      }
    }
  }

  test("WebSocket rejects invalid UTF-8 across fragment boundaries") {
    protocolGatewayTest {
      socket {
        send(Frame.Text(false, byteArrayOf(0xe2.toByte())))
        incoming.receive()
        send(Frame.Text(true, byteArrayOf(0x28)))
        receiveCloseReason().code shouldBe 1007.toShort()
      }
    }
  }

  test("WebSocket idle deadline and access revocation close both peers") {
    protocolGatewayTest(idle = 150.milliseconds) {
      socket("/sockets/idle") {
        receiveCloseReason().code shouldBe 1001.toShort()
      }
      state.socketClosed.await()
    }
    protocolGatewayTest {
      socket("/sockets/idle") {
        revoked.complete(Unit)
        receiveCloseReason().code shouldBe 1008.toShort()
      }
      state.socketClosed.await()
    }
  }
})
