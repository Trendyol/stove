package stove.ktor.bff.tests

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.nondeterministic.eventually
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.http.content.OutgoingContent
import io.ktor.server.response.respondText
import io.ktor.utils.io.*
import kotlinx.coroutines.*
import kotlinx.io.readByteArray
import stove.ktor.bff.fixtures.*
import stove.ktor.oidc.client.TokenBinding
import java.io.IOException
import java.io.EOFException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.seconds

class GatewayStreamingTest : FunSpec({
  for (path in listOf("/api/download", "/custom")) {
    test("$path delivers the first response bytes before the upstream finishes") {
      val after = AtomicInteger()
      streamingGatewayTest(configure = {
        afterForward("/api/**") {
          after.incrementAndGet()
          response.headers["X-Stream"] = "intercepted"
        }
      }) {
        client.prepareGet("$origin$path").execute { response ->
          response.status shouldBe HttpStatusCode.OK
          val body = response.bodyAsChannel()
          body.readByte() shouldBe 'f'.code.toByte()
          state.downloadFinished.isCompleted shouldBe false
          after.get() shouldBe if (path == "/custom") 0 else 1
          response.headers["X-Stream"] shouldBe if (path == "/custom") "upstream" else "intercepted"
          state.releaseDownload.complete(Unit)
          body.readBuffer().readByteArray().decodeToString() shouldBe "irstsecond"
        }
      }
    }
  }

  test("uploads reach the upstream before the browser finishes producing the request") {
    streamingGatewayTest(configure = {
      beforeForward("/api/**") { request.headers[HttpHeaders.ContentType] = ContentType.Text.Plain.toString() }
    }) {
      coroutineScope {
        val upload = GatedUpload()
        val request = async { client.post("$origin/api/upload") { setBody(upload) } }
        state.uploadStarted.await()
        state.uploadBytes.get() shouldBe 5
        upload.finish()
        request.await().bodyAsText() shouldBe "11"
      }
    }
  }

  test("response replacement and hook failures do not wait for an unread upstream body") {
    for (fail in listOf(false, true)) {
      streamingGatewayTest(configure = {
        afterForward("/api/**") {
          if (fail) throw IOException("application hook failed")
          response.textBody("replacement")
        }
      }) {
        val result = client.get("$origin/api/download")
        result.status shouldBe if (fail) HttpStatusCode.InternalServerError else HttpStatusCode.OK
        result.bodyAsText() shouldBe if (fail) "application hook failed" else "replacement"
        state.releaseDownload.isCompleted shouldBe false
      }
    }
  }

  test("reading the complete response requires an explicit buffer limit and preserves it for forwarding") {
    streamingGatewayTest(configure = {
      afterForward("/api/**") {
        val bytes = response.body.readBytes(maxBytes = 32)
        bytes.decodeToString() shouldBe "firstsecond"
        // The returned snapshot does not silently mutate the forwarded representation.
        bytes.fill(0)
      }
    }) {
      state.releaseDownload.complete(Unit)
      client.get("$origin/api/download").bodyAsText() shouldBe "firstsecond"
    }
  }

  for ((bufferLimit, routeLimit) in listOf(8 to 32, 32 to 8)) {
    test("explicit body reads enforce the smaller limit: buffer $bufferLimit and route $routeLimit") {
      streamingGatewayTest(limit = routeLimit, configure = {
        afterForward("/api/**") { response.body.readBytes(maxBytes = bufferLimit) }
      }) {
        state.releaseDownload.complete(Unit)
        client.get("$origin/api/download").status shouldBe HttpStatusCode.BadGateway
      }
    }
  }

  test("application supplied response streams forward before completion and release discarded streams") {
    val discarded = ByteChannel()
    val replacement = ByteChannel(autoFlush = true)
    replacement.writeStringUtf8("replacement")
    streamingGatewayTest(configure = {
      afterForward("/api/**") {
        response.streamBody(discarded)
        response.streamBody(replacement)
      }
    }) {
      client.prepareGet("$origin/api/download").execute { response ->
        val body = response.bodyAsChannel()
        body.readByte() shouldBe 'r'.code.toByte()
        discarded.isClosedForRead shouldBe true
        state.releaseDownload.isCompleted shouldBe false
        replacement.writeStringUtf8(" end")
        replacement.flushAndClose()
        body.readBuffer().readByteArray().decodeToString() shouldBe "eplacement end"
      }
    }
  }

  test("a request stream installed by a short-circuiting hook is released") {
    val unread = ByteChannel()
    streamingGatewayTest(configure = {
      beforeForward("/api/**") {
        request.streamBody(unread)
        call.respondText("local")
      }
    }) {
      client.post("$origin/api/upload").bodyAsText() shouldBe "local"
      eventually(1.seconds) { unread.isClosedForRead shouldBe true }
      state.uploadStarted.isCompleted shouldBe false
    }
  }

  for (overflow in listOf(false, true)) {
    test("an unknown-length response ${if (overflow) "aborts on overflow" else "finishes with a terminal chunk"}") {
      streamingGatewayTest(limit = 32) {
        withChunkedResponse(origin, if (overflow) "/api/overflow" else "/api/download") {
          status shouldBe HttpStatusCode.OK
          readChunk().decodeToString() shouldBe "first"
          state.releaseDownload.complete(Unit)
          if (overflow) {
            shouldThrow<EOFException> { readChunk() }
          } else {
            readChunk().decodeToString() shouldBe "second"
            readChunk().size shouldBe 0
          }
        }
      }
    }
  }

  test("an unknown-length upload exceeding its limit stops the upstream transfer") {
    streamingGatewayTest(limit = 32) {
      coroutineScope {
        val upload = GatedUpload(ByteArray(128))
        val request = async { client.post("$origin/api/upload") { setBody(upload) } }
        state.uploadStarted.await()
        upload.finish()
        request.await().status shouldBe HttpStatusCode.PayloadTooLarge
        state.uploadBytes.get() shouldBeLessThan 33
      }
    }
  }

  test("a cancelled download releases the upstream without draining its remaining payload") {
    streamingGatewayTest {
      client.prepareGet("$origin/api/cancel").execute { response ->
        response.bodyAsChannel().readByte() shouldBe 'f'.code.toByte()
      }
      state.releaseDownload.complete(Unit)
      state.downloadFinished.await()
      state.producedBytes.get() shouldBeLessThan 32 * 1024 * 1024L
    }
  }

  test("DPoP never buffers or replays an upload when a nonce challenge arrives") {
    streamingGatewayTest(binding = TokenBinding.Dpop()) {
      val response = client.post("$origin/api/nonce-gated") {
        setBody(object : OutgoingContent.WriteChannelContent() {
          override suspend fun writeTo(channel: ByteWriteChannel) = channel.writeFully("upload".toByteArray())
        })
      }
      response.status shouldBe HttpStatusCode.Unauthorized
      state.nonceCalls.get() shouldBe 1
      invalidations.get() shouldBe 1
      state.releaseDownload.isCompleted shouldBe false
    }
  }

  test("explicitly generated request bodies remain replayable for a DPoP nonce retry") {
    val before = AtomicInteger()
    val after = AtomicInteger()
    streamingGatewayTest(binding = TokenBinding.Dpop(), configure = {
      beforeForward("/api/**") {
        before.incrementAndGet()
        request.textBody("generated command")
      }
      afterForward("/api/**") { after.incrementAndGet() }
    }) {
      client.post("$origin/api/nonce").bodyAsText() shouldBe "accepted"
      state.nonceCalls.get() shouldBe 2
      before.get() shouldBe 1
      after.get() shouldBe 1
    }
  }
})
