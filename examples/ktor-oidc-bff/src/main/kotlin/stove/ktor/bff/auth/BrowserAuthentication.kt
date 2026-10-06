package stove.ktor.bff.auth

import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.sessions.*

internal data class LoginCookie(val id: String)
internal data class BrowserCookie(val id: String)
internal data class AuthenticatedBrowser(val id: String, val session: UserSession)

internal fun Application.browserAuthentication(store: BrowserSessions, secureCookies: Boolean) {
  install(Sessions) {
    cookie<LoginCookie>("bff_login") {
      serializer = OpaqueCookieSerializer(::LoginCookie) { it.id }
      browserCookie(secureCookies, 300)
    }
    cookie<BrowserCookie>("bff_session") {
      serializer = OpaqueCookieSerializer(::BrowserCookie) { it.id }
      browserCookie(secureCookies, 1800)
    }
  }
  install(Authentication) {
    session<BrowserCookie>("browser") {
      validate { cookie ->
        try {
          AuthenticatedBrowser(cookie.id, store.session(cookie.id))
        } catch (_: LoginRequired) {
          null // Ktor's authentication boundary represents invalid credentials with no principal.
        }
      }
      challenge { throw LoginRequired() }
    }
  }
}

internal fun ApplicationCall.authenticatedBrowser(): AuthenticatedBrowser = checkNotNull(principal<AuthenticatedBrowser>("browser")) {
  "This route must be inside authenticate(\"browser\")"
}

private fun <T : Any> CookieSessionBuilder<T>.browserCookie(secure: Boolean, maxAge: Long) {
  sendOnlyIfModified = true
  cookie.path = "/"
  cookie.httpOnly = true
  cookie.secure = secure
  cookie.maxAgeInSeconds = maxAge
  cookie.extensions["SameSite"] = "Lax"
}

/** Explicit serialization keeps browser values opaque and avoids reflective serialization in native images. */
private class OpaqueCookieSerializer<T : Any>(val read: (String) -> T, val write: (T) -> String) : SessionSerializer<T> {
  override fun deserialize(text: String): T = read(text)
  override fun serialize(session: T): String = write(session)
}
