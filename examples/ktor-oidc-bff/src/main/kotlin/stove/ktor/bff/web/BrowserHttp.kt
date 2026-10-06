package stove.ktor.bff.web

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.response.*
import io.ktor.server.sessions.*
import kotlinx.serialization.json.*
import stove.ktor.bff.auth.*
import stove.ktor.bff.auth.storage.SessionStorageUnavailable

internal fun Application.browserHttp(errors: StatusPagesConfig.() -> Unit) {
  install(BrowserHeaders)
  install(StatusPages) {
    exception<LoginRequired> { call, _ ->
      call.sessions.clear<BrowserCookie>()
      call.problem(HttpStatusCode.Unauthorized, "Please sign in")
    }
    exception<LoginRejected> { call, _ -> call.problem(HttpStatusCode.BadRequest, "Login rejected; please sign in again") }
    exception<CsrfRejected> { call, _ -> call.problem(HttpStatusCode.Forbidden, "Invalid CSRF token or request origin") }
    exception<SessionStorageUnavailable> { call, _ ->
      call.problem(HttpStatusCode.ServiceUnavailable, "Session storage unavailable; please try again")
    }
    exception<ProviderUnavailable> { call, error ->
      this@browserHttp.log.warn("OIDC provider unavailable: {}", error.message)
      call.problem(HttpStatusCode.BadGateway, "Identity provider unavailable; please try again")
    }
    errors()
  }
}

private val BrowserHeaders = createApplicationPlugin("BrowserHeaders") {
  onCall { call ->
    call.response.header(HttpHeaders.CacheControl, "no-store")
    call.response.header("Referrer-Policy", "no-referrer")
    call.response.header("X-Content-Type-Options", "nosniff")
    call.response.header("Content-Security-Policy", "default-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'")
  }
}

internal suspend fun ApplicationCall.problem(status: HttpStatusCode, message: String) =
  respondText(buildJsonObject { put("error", message) }.toString(), ContentType.Application.Json, status)
