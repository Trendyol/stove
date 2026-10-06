package stove.ktor.oidc.server

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import java.security.MessageDigest

class SessionCsrfConfig {
  lateinit var trustedOrigin: String
  lateinit var token: (ApplicationCall) -> String
  var headerName = "X-CSRF-Token"
}

/** Install inside authenticated routes that accept cookie-authenticated mutations. */
val SessionCsrf = createRouteScopedPlugin("SessionCsrf", ::SessionCsrfConfig) {
  val origin = pluginConfig.trustedOrigin
  val token = pluginConfig.token
  val header = pluginConfig.headerName
  on(AuthenticationChecked) { call ->
    if (call.isHandled || call.request.httpMethod in listOf(HttpMethod.Get, HttpMethod.Head, HttpMethod.Options)) return@on
    val expected = token(call)
    val received = call.request.headers.getAll(header)?.singleOrNull().orEmpty()
    val trusted = call.request.headers.getAll(HttpHeaders.Origin)?.singleOrNull() == origin
    if (trusted && expected.isNotBlank() && MessageDigest.isEqual(expected.toByteArray(), received.toByteArray())) return@on
    call.oidcProblem(HttpStatusCode.Forbidden, "invalid_csrf", "Invalid CSRF token or request origin")
  }
}
