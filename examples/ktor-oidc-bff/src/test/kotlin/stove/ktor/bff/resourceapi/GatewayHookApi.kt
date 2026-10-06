package stove.ktor.bff.resourceapi

import io.ktor.http.*
import io.ktor.http.content.OutgoingContent
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.delay
import kotlinx.serialization.json.*
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream

/** A real upstream for focused gateway tests; provider-backed security is exercised by ResourceApi. */
fun Application.gatewayHookApi(onRequest: () -> Unit) {
  routing {
    route("/{path...}") {
      handle {
        onRequest()
        check(call.request.headers[HttpHeaders.Authorization] == "Bearer upstream-token")
        call.hookResponse()
      }
    }
  }
}

private suspend fun ApplicationCall.hookResponse() {
  when (request.path()) {
    "/orders/denied" -> respondText("Denied", status = HttpStatusCode.Forbidden)

    "/orders/unauthorized" -> respondText("Invalid token", status = HttpStatusCode.Unauthorized)

    "/orders/redirect" -> respondRedirect("/orders/42")

    "/orders/oversized" -> respondText("x".repeat(513))

    "/orders/slow" -> {
      delay(500)
      respondText("Late")
    }

    "/orders/metadata" -> respond(HookMetadata(HttpStatusCode.OK))

    "/orders/conditional" -> respond(HookMetadata(HttpStatusCode.NotModified))

    "/orders/empty" -> respond(HttpStatusCode.NoContent)

    "/orders/encoded" -> encodedResponse()

    else -> echoGatewayRequest()
  }
}

private suspend fun ApplicationCall.encodedResponse() {
  response.header(HttpHeaders.ContentEncoding, "gzip")
  response.header(HttpHeaders.ETag, "\"upstream\"")
  response.header(HttpHeaders.LastModified, "Mon, 05 Oct 2026 10:00:00 GMT")
  val buffer = ByteArrayOutputStream()
  GZIPOutputStream(buffer).use { it.write("original".toByteArray()) }
  respondBytes(buffer.toByteArray(), ContentType.Text.Plain)
}

private class HookMetadata(override val status: HttpStatusCode) : OutgoingContent.NoContent() {
  override val contentLength = 4096L
}

private suspend fun ApplicationCall.echoGatewayRequest() {
  val payload = buildJsonObject {
    put("path", request.path())
    put("method", request.httpMethod.value)
    put("body", receiveText())
    put("channel", request.headers["X-Order-Channel"].orEmpty())
    put("cookieReceived", HttpHeaders.Cookie in request.headers)
  }
  respondText(payload.toString(), ContentType.Application.Json)
}
