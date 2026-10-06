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

class GatewaySseTest : FunSpec({
  test("SSE socket idle timeout before headers is a gateway timeout") {
    protocolGatewayTest(idle = 75.milliseconds) {
      client.get("$origin/events/slow").status shouldBe HttpStatusCode.GatewayTimeout
    }
  }

  test("disconnecting the browser releases the upstream event producer") {
    protocolGatewayTest {
      client.prepareGet("$origin/events/disconnect").execute { it.bodyAsChannel().readByte() }
      withTimeout(2_000) { state.eventStreamClosed.await() }
    }
  }

  test("SSE forwards events, comments, IDs and retry fields before completion with handshake hooks") {
    val before = AtomicInteger()
    val after = AtomicInteger()
    protocolGatewayTest(configure = {
      beforeForward("/events/**") { before.incrementAndGet() }
      afterForward("/events/**") {
        after.incrementAndGet()
        response.headers["X-Hook"] = "events"
      }
    }) {
      client.prepareGet("$origin/events/live") { header("Last-Event-ID", "resume-42") }.execute {
        it.headers["X-Hook"] shouldBe "events"
        val body = it.bodyAsChannel()
        body.readLineStrict() shouldBe ": heartbeat"
        body.readLineStrict() shouldBe "id: resume-42"
        body.readLineStrict() shouldBe "retry: 1000"
        body.readLineStrict() shouldBe "data: hello"
        body.readLineStrict() shouldBe ""
        before.get() shouldBe 1
        after.get() shouldBe 1
        state.releaseEvents.isCompleted shouldBe false
        state.releaseEvents.complete(Unit)
        body.readBuffer().readByteArray().decodeToString() shouldBe "data: goodbye\n\n"
      }
    }
  }

  test("SSE stays open beyond the handshake timeout and total HTTP body limit") {
    protocolGatewayTest {
      client.get("$origin/events/active").bodyAsText() shouldBe (0..7).joinToString("") { "data: $it\n\n" }
    }
  }

  test("SSE validates content type, preserves 204 and times out waiting for headers") {
    protocolGatewayTest {
      client.get("$origin/events/invalid").status shouldBe HttpStatusCode.BadGateway
      client.get("$origin/events/end").status shouldBe HttpStatusCode.NoContent
      client.get("$origin/events/slow").status shouldBe HttpStatusCode.GatewayTimeout
    }
  }

  test("SSE idle timeout aborts the HTTP stream without a successful terminal chunk") {
    protocolGatewayTest(idle = 150.milliseconds) {
      withChunkedResponse(origin, "/events/idle") {
        readChunk().decodeToString() shouldBe ": open\n\n"
        shouldThrow<EOFException> { readChunk() }
      }
    }
  }

  test("revoking SSE access aborts an established idle stream") {
    protocolGatewayTest {
      withChunkedResponse(origin, "/events/idle") {
        readChunk().decodeToString() shouldBe ": open\n\n"
        revoked.complete(Unit)
        shouldThrow<EOFException> { readChunk() }
      }
    }
  }
})
