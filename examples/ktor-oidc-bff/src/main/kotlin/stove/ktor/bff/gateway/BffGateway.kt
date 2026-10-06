package stove.ktor.bff.gateway

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.*
import io.ktor.server.plugins.statuspages.StatusPagesConfig
import io.ktor.server.routing.Route
import io.ktor.server.sessions.*
import stove.ktor.bff.auth.*
import stove.ktor.bff.auth.storage.*
import stove.ktor.bff.bff
import stove.ktor.bff.config.BffConfiguration
import stove.ktor.bff.web.problem
import stove.ktor.gateway.*

/** Add authenticated forwarding to a BFF. Custom routes inherit the same session and CSRF policies. */
fun Application.bffWithGateway(
  configuration: BffConfiguration,
  provider: OidcClient,
  storage: BrowserSessionStore,
  forwarding: BffGatewayConfiguration,
  sessionPolicy: SessionPolicy = SessionPolicy(),
  gateway: GatewayHooks.() -> Unit = {},
  errors: StatusPagesConfig.() -> Unit = {},
  customRoutes: Route.() -> Unit = {}
) {
  require(forwarding.enabled) { "Configure at least one gateway route, or use bff for custom routes only" }
  install(Gateway) {
    routes = forwarding.routes
    webSocketOrigins = setOf(configuration.origin)
    hooks = GatewayHooks().apply(gateway)
    oidc { call ->
      val active = call.authenticatedBrowser().session
      OidcGatewayAccess(
        binding = active.binding,
        accessToken = { provider.accessToken(active) },
        invalidate = {
          active.close()
          throw LoginRequired()
        },
        revoked = { active.awaitClosed() }
      )
    }
  }
  bff(
    configuration = configuration.copy(additionalScopes = configuration.additionalScopes + forwarding.routes.scopes),
    provider = provider,
    storage = storage,
    sessionPolicy = sessionPolicy,
    errors = {
      exception<GatewayFailure> { call, error ->
        if (call.response.isCommitted) throw error
        if (error.status == HttpStatusCode.Unauthorized) call.sessions.clear<BrowserCookie>()
        call.problem(error.status, error.message.orEmpty())
      }
      errors()
    }
  ) {
    gateway()
    customRoutes()
  }
}
