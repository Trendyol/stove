package stove.ktor.bff

import io.ktor.server.application.*
import io.ktor.server.auth.authenticate
import io.ktor.server.plugins.statuspages.StatusPagesConfig
import io.ktor.server.routing.*
import stove.ktor.bff.auth.*
import stove.ktor.bff.auth.storage.*
import stove.ktor.bff.config.BffConfiguration
import stove.ktor.bff.web.*
import stove.ktor.oidc.client.TokenBinding
import stove.ktor.oidc.server.SessionCsrf

/** Browser authentication and CSRF-protected application routes, independent of any proxy transport. */
fun Application.bff(
  configuration: BffConfiguration,
  provider: OidcClient,
  storage: BrowserSessionStore,
  sessionPolicy: SessionPolicy = SessionPolicy(),
  errors: StatusPagesConfig.() -> Unit = {},
  customRoutes: Route.() -> Unit = {}
) {
  val store = BrowserSessions(storage, sessionPolicy) { if (configuration.dpop) TokenBinding.Dpop() else TokenBinding.Bearer }
  sessionMaintenance(storage)
  browserAuthentication(store, configuration.secureCookies)
  browserHttp(errors)
  routing {
    webRoutes()
    loginRoutes(store, provider, configuration.scopes)
    authenticate("browser") {
      install(SessionCsrf) {
        trustedOrigin = configuration.origin
        token = { it.authenticatedBrowser().session.csrfToken }
      }
      sessionRoutes(store, provider)
      customRoutes()
    }
  }
}
