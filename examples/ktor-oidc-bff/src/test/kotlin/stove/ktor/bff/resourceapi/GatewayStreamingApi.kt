package stove.ktor.bff.resourceapi

import com.nimbusds.jwt.SignedJWT
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.utils.io.*
import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class StreamingApiState {
  val releaseDownload = CompletableDeferred<Unit>()
  val downloadFinished = CompletableDeferred<Unit>()
  val uploadStarted = CompletableDeferred<Unit>()
  val uploadBytes = AtomicLong()
  val producedBytes = AtomicLong()
  val nonceCalls = AtomicInteger()
}

/** Gates expose eager buffering: a consumer must observe the first chunk before the producer may finish. */
fun Application.gatewayStreamingApi(state: StreamingApiState) {
  routing {
    get("/stream/download") { call.download(state, 1) }
    get("/stream/overflow") { call.download(state, 1, ByteArray(512)) }
    get("/stream/cancel") { call.download(state, 4096, ByteArray(8 * 1024)) }
    post("/stream/upload") { call.upload(state) }
    route("/stream/nonce") { handle { call.challenge(state, false) } }
    route("/stream/nonce-gated") { handle { call.challenge(state, true) } }
  }
}

private suspend fun ApplicationCall.download(state: StreamingApiState, chunks: Int, chunk: ByteArray = "second".toByteArray()) {
  response.header("X-Stream", "upstream")
  respondBytesWriter(ContentType.Application.OctetStream) {
    try {
      writeFully("first".toByteArray())
      flush()
      state.releaseDownload.await()
      repeat(chunks) {
        writeFully(chunk)
        flush()
        state.producedBytes.addAndGet(chunk.size.toLong())
      }
    } finally {
      state.downloadFinished.complete(Unit)
    }
  }
}

private suspend fun ApplicationCall.upload(state: StreamingApiState) {
  val input = receiveChannel()
  val buffer = ByteArray(8 * 1024)
  while (input.awaitContent()) {
    val count = input.readAvailable(buffer)
    if (count < 0) break
    state.uploadBytes.addAndGet(count.toLong())
    state.uploadStarted.complete(Unit)
  }
  respondText(state.uploadBytes.get().toString())
}

private suspend fun ApplicationCall.challenge(state: StreamingApiState, gated: Boolean) {
  state.nonceCalls.incrementAndGet()
  val proof = SignedJWT.parse(request.headers["DPoP"])
  if (proof.jwtClaimsSet.getStringClaim("nonce") == "stream-nonce") return respondText("accepted")
  response.header("DPoP-Nonce", "stream-nonce")
  response.header(HttpHeaders.WWWAuthenticate, "DPoP error=\"use_dpop_nonce\"")
  if (!gated) return respondText("""{"error":"use_dpop_nonce"}""", status = HttpStatusCode.Unauthorized)
  respondBytesWriter(ContentType.Application.Json, HttpStatusCode.Unauthorized) {
    writeFully("""{"error":"use_dpop_nonce"}""".toByteArray())
    flush()
    state.releaseDownload.await()
  }
}
