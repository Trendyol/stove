package stove.ktor.bff.resourceapi

import io.ktor.http.*
import io.ktor.http.content.OutgoingContent
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.delay
import kotlinx.serialization.json.*
import stove.ktor.oidc.server.VerifiedAccessToken

/** Observable API endpoints for gateway forwarding and failure scenarios. */
internal fun Route.gatewayEndpoints() {
  get("/orders/{id}/items") { call.describeRequest() }
  post("/orders/{id}/items") { call.describeRequest(call.receiveText()) }
  patch("/orders/{id}") { call.respond(HttpStatusCode.NoContent) }
  patch("/orders/{id}/confirmation") { call.describeRequest(call.receiveText()) }
  delete("/orders/{id}") { call.respond(HttpStatusCode.NoContent) }
  get("/orders/slow") {
    delay(500)
    call.respondText("ready")
  }
  get("/orders/large") { call.respondText("x".repeat(128)) }
  get("/orders/redirect") { call.respondRedirect("/health") }
  get("/orders/headers") {
    call.response.header("Set-Cookie", "upstream-session=internal")
    call.response.header("X-Internal-Secret", "internal")
    call.response.header("ETag", "\"v1\"")
    call.respondText("headers")
  }
  get("/inventory/{id}") { call.describeRequest() }
  post("/inventory/{id}") { call.respond(HttpStatusCode.Accepted) }

  head("/orders/large") { call.respond(RepresentationMetadata(HttpStatusCode.OK)) }
  get("/orders/conditional") {
    call.response.header(HttpHeaders.ETag, "\"v1\"")
    if (call.request.headers[HttpHeaders.IfNoneMatch] == "\"v1\"") {
      call.respond(RepresentationMetadata(HttpStatusCode.NotModified))
    } else {
      call.respondText("x".repeat(128))
    }
  }
  get("/orders/challenge") {
    call.response.header("DPoP-Nonce", "bounded-challenge")
    call.respondText("x".repeat(64 * 1024 + 1), status = HttpStatusCode.BadRequest)
  }
}

private class RepresentationMetadata(override val status: HttpStatusCode) : OutgoingContent.NoContent() {
  override val contentLength = 128L
}

private suspend fun ApplicationCall.describeRequest(body: String = "") {
  val result = buildJsonObject {
    put("subject", checkNotNull(principal<VerifiedAccessToken>("access")).subject)
    put("method", request.httpMethod.value)
    put("path", request.path())
    put("query", request.queryParameters["q"].orEmpty())
    put("body", body)
    put("cookieReceived", HttpHeaders.Cookie in request.headers)
    put("csrfReceived", "X-CSRF-Token" in request.headers)
    put("forwardedReceived", HttpHeaders.Forwarded in request.headers)
    put("spoofedUserReceived", "X-User-Id" in request.headers)
    put("idempotencyKey", request.headers["Idempotency-Key"].orEmpty())
  }
  respondText(result.toString(), ContentType.Application.Json)
}
