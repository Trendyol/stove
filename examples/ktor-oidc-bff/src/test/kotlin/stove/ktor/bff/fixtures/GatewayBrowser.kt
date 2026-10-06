package stove.ktor.bff.fixtures

import com.trendyol.stove.oidc.*
import com.trendyol.stove.system.ValidationDsl
import stove.ktor.bff.StoveConfig

/** A fresh browser session usable with either NAV or Keycloak. */
suspend fun ValidationDsl.gatewayBrowser(): GatewayBrowser {
  if (!StoveConfig.keycloak) {
    oidc {
      mock.whenTokenRequested(TokenRequestMatch.authorizationCode("stove-bff")) {
        subject = "alice"
        audience("orders-api")
        scopes("orders:read")
      }
    }
  }
  val flow = BrowserFlow(this)
  val cookie = flow.signIn()
  val session = flow.session(cookie)
  return GatewayBrowser(session.subject, mapOf("Cookie" to cookie, "Origin" to StoveConfig.origin, "X-CSRF-Token" to session.csrfToken))
}

data class GatewayBrowser(val subject: String, val headers: Map<String, String>)
