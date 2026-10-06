package stove.ktor.bff.auth

import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.sessions.*
import kotlinx.serialization.json.*

internal fun Route.loginRoutes(store: BrowserSessions, provider: OidcClient, scopes: String) {
  get("/auth/login") {
    val attempt = store.beginLogin()
    call.sessions.set(LoginCookie(attempt.state))
    call.respondRedirect(provider.authorizationUrl(attempt, scopes))
  }
  get("/auth/callback") {
    val attempt = store.finishLogin(call.request.queryParameters["state"].orEmpty(), call.sessions.get<LoginCookie>()?.id.orEmpty())
    call.sessions.clear<LoginCookie>()
    val code = call.request.queryParameters["code"]?.takeIf { it.isNotBlank() }
      ?: throw LoginRejected("Authorization code missing or access denied")
    val tokens = provider.exchange(code, attempt)
    val id = store.createSession(tokens, attempt)
    call.sessions.set(BrowserCookie(id))
    call.respondRedirect("/")
  }
}

/** Mount with browser authentication and CSRF, alongside the protected proxy routes. */
internal fun Route.sessionRoutes(store: BrowserSessions, provider: OidcClient) {
  get("/api/session") {
    val session = call.authenticatedBrowser().session
    val body = buildJsonObject {
      put("subject", session.subject)
      put("csrfToken", session.csrfToken)
    }
    call.respondText(body.toString(), ContentType.Application.Json)
  }
  get("/api/profile") {
    val profile = provider.profile(call.authenticatedBrowser().session)
    call.respondText(profile.toString(), ContentType.Application.Json)
  }
  post("/auth/logout") {
    val browser = call.authenticatedBrowser()
    store.logout(browser.id, browser.session.csrfToken)
    call.sessions.clear<BrowserCookie>()
    call.respond(HttpStatusCode.NoContent)
  }
}
