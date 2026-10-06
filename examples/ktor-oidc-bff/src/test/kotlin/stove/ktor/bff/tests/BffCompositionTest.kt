package stove.ktor.bff.tests

import com.trendyol.stove.oidc.*
import com.trendyol.stove.system.stove
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.respondText
import io.ktor.server.routing.*
import io.ktor.server.testing.testApplication
import io.ktor.server.websocket.WebSockets
import stove.ktor.bff.BffResources
import stove.ktor.bff.StoveConfig
import stove.ktor.bff.auth.authenticatedBrowser
import stove.ktor.bff.bff
import stove.ktor.bff.config.BffConfiguration
import stove.ktor.bff.fixtures.*
import stove.ktor.bff.gateway.*
import stove.ktor.gateway.*
import stove.ktor.gateway.Gateway
import stove.ktor.gateway.RuntimeKey

class BffCompositionTest : FunSpec({
  test("custom-route BFF logs in and protects sessions without installing any gateway transport") {
    stove {
      val issuer = oidc { endpoints.issuerUrl }
      oidc { mock.whenTokenRequested(TokenRequestMatch.authorizationCode("stove-bff")) { subject = "core-user" } }
      val providerBrowser = BrowserFlow(this)
      val configuration = BffConfiguration.load(emptyArray(), mapOf("BFF_ORIGIN" to StoveConfig.origin, "OIDC_ISSUER" to issuer, "OIDC_SCOPES" to "custom:read"))
      BffResources.open(configuration).use { resources ->
        testApplication {
          application {
            bff(configuration, resources.provider, resources.storage) {
              get("/api/custom") { call.respondText(call.authenticatedBrowser().session.subject) }
              post("/api/custom") { call.respondText("saved") }
            }
            pluginOrNull(Gateway) shouldBe null
            pluginOrNull(WebSockets) shouldBe null
            attributes.contains(RuntimeKey) shouldBe false
          }
          val http = createClient { followRedirects = false }
          http.get("/health").status shouldBe HttpStatusCode.OK
          http.get("/api/custom").status shouldBe HttpStatusCode.Unauthorized
          val browser = TestBrowser(http)
          val login = browser.begin()
          login.url.parameters["scope"] shouldBe "openid profile email custom:read"
          val cookie = browser.complete(login, providerBrowser.authorize(login))
          val session = browser.session(cookie)
          session.subject shouldBe "core-user"
          http.get("/api/custom") { header(HttpHeaders.Cookie, cookie) }.bodyAsText() shouldBe "core-user"
          http.get("/api/profile") { header(HttpHeaders.Cookie, cookie) }.status shouldBe HttpStatusCode.OK
          http.get("/api/orders") { header(HttpHeaders.Cookie, cookie) }.status shouldBe HttpStatusCode.NotFound
          http.post("/api/custom") { header(HttpHeaders.Cookie, cookie) }.status shouldBe HttpStatusCode.Forbidden
          http.post("/api/custom") {
            header(HttpHeaders.Cookie, cookie)
            header(HttpHeaders.Origin, "https://wrong.example")
            header("X-CSRF-Token", session.csrfToken)
          }.status shouldBe HttpStatusCode.Forbidden
          for (path in listOf("/api/custom", "/auth/logout")) {
            val response = http.post(path) {
              header(HttpHeaders.Cookie, cookie)
              header(HttpHeaders.Origin, configuration.origin)
              header("X-CSRF-Token", session.csrfToken)
            }
            response.status shouldBe if (path == "/auth/logout") HttpStatusCode.NoContent else HttpStatusCode.OK
          }
          http.get("/api/custom") { header(HttpHeaders.Cookie, cookie) }.status shouldBe HttpStatusCode.Unauthorized
        }
      }
    }
  }

  test("gateway composition adds service scopes to a provider opened with core configuration") {
    stove {
      val configuration = BffConfiguration.load(emptyArray(), mapOf("OIDC_ISSUER" to oidc { endpoints.issuerUrl }, "OIDC_SCOPES" to "custom:read profile"))
      val forwarding = BffGatewayConfiguration(
        gatewayRoutes {
          service("orders", "https://orders.example") {
            scopes("orders:read", "custom:read")
            route("/orders/**")
          }
        }
      )
      BffResources.open(configuration).use { resources ->
        testApplication {
          application {
            bffWithGateway(configuration, resources.provider, resources.storage, forwarding)
            attributes.contains(RuntimeKey) shouldBe true
          }
          val browser = TestBrowser(createClient { followRedirects = false })
          browser.begin().url.parameters["scope"] shouldBe "openid profile email custom:read orders:read"
          configuration.scopes shouldBe "openid profile email custom:read"
        }
      }
    }
  }
})
