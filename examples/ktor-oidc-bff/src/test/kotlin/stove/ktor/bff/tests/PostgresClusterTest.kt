package stove.ktor.bff.tests

import arrow.core.None
import com.trendyol.stove.http.*
import com.trendyol.stove.oidc.*
import com.trendyol.stove.postgres.postgresql
import com.trendyol.stove.system.stove
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import stove.ktor.bff.StoveConfig
import stove.ktor.bff.fixtures.*

class PostgresClusterTest : FunSpec({
  test("logout on another replica terminates an established WebSocket") {
    stove {
      val browser = gatewayBrowser()
      BffReplica.start(oidc { endpoints.issuerUrl }).use { replica ->
        HttpClient(CIO).use { client ->
          browser.shouldUseAuthenticatedWebSocket {
            client.post("${replica.origin}/auth/logout") {
              browser.headers.forEach { (name, value) -> header(name, value) }
            }.status shouldBe HttpStatusCode.NoContent
          }
        }
      }
      postgresql { shouldHaveNoSession(browser.headers.getValue("Cookie")) }
    }
  }

  test("login starts on one replica, callback completes on another, and its session survives replica replacement") {
    stove {
      val issuer = oidc { endpoints.issuerUrl }
      val browser = BrowserFlow(this)
      val login = browser.begin()
      val callback = browser.authorize(login)
      val cookie = BffReplica.start(issuer).use { replica ->
        HttpClient(CIO) { followRedirects = false }.use { client ->
          val response = client.get(replica.origin + callback.encodedPath + "?" + callback.encodedQuery) {
            header(HttpHeaders.Cookie, login.cookie)
          }
          response.status shouldBe HttpStatusCode.Found
          checkNotNull(response.headers.getAll(HttpHeaders.SetCookie)).single { it.startsWith("bff_session=") }.substringBefore(';')
        }
      }
      val session = browser.session(cookie)
      lateinit var initial: SessionRow
      postgresql {
        shouldHaveNoLogin(login.url.query().getValue("state"))
        initial = sessionRow(cookie)
        initial.version shouldBe 0
        makeRefreshDue(cookie)
      }
      BffReplica.start(issuer).use { replacement ->
        HttpClient(CIO).use { client ->
          // The HTTP DSL targets registered base URLs; this replica uses an ephemeral address.
          coroutineScope {
            listOf(StoveConfig.origin, replacement.origin).flatMap { origin ->
              List(4) {
                async {
                  val response = client.get("$origin/api/profile") { header(HttpHeaders.Cookie, cookie) }
                  response.status shouldBe HttpStatusCode.OK
                  Json.parseToJsonElement(response.bodyAsText()).jsonObject.getValue("sub").jsonPrimitive.content shouldBe session.subject
                }
              }
            }.awaitAll()
          }
          postgresql {
            val renewed = sessionRow(cookie)
            renewed.version shouldBe initial.version + 2
            renewed.deadline shouldBe initial.deadline
            renewed.binding shouldBe initial.binding
            renewed.state.text("type") shouldBe "ready"
            renewed.state.text("refreshToken") shouldNotBe initial.state.text("refreshToken")
          }
          http {
            postAndExpectBodilessResponse(
              "/auth/logout",
              None,
              headers = mapOf("Cookie" to cookie, "Origin" to StoveConfig.origin, "X-CSRF-Token" to session.csrfToken)
            ) { it.status shouldBe 204 }
          }
          client.get("${replacement.origin}/api/session") { header(HttpHeaders.Cookie, cookie) }.status shouldBe HttpStatusCode.Unauthorized
          postgresql { shouldHaveNoSession(cookie) }
        }
      }
    }
  }
})
