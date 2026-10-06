package stove.ktor.bff.tests

import arrow.core.None
import com.trendyol.stove.http.*
import com.trendyol.stove.oidc.*
import com.trendyol.stove.system.stove
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.http.*
import kotlinx.coroutines.delay
import stove.ktor.bff.StoveConfig
import stove.ktor.bff.fixtures.*
import java.time.Instant
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class BffTest : FunSpec({
  test("home and static assets are packaged in the executable") {
    stove {
      http {
        getResponse<String>("/") {
          it.status shouldBe 200
          it.body() shouldContain "A real OIDC login"
        }
        getResponse<String>("/app.js") {
          it.status shouldBe 200
          it.body() shouldContain "/api/session"
        }
        getBodilessResponse("/style.css") { it.status shouldBe 200 }
      }
    }
  }

  test("anonymous and invented sessions cannot read a profile") {
    stove {
      http {
        getBodilessResponse("/api/session") { it.status shouldBe 401 }
        getBodilessResponse("/api/profile", headers = mapOf("Cookie" to "bff_session=invented")) { it.status shouldBe 401 }
      }
    }
  }

  test("login uses PKCE and keeps tokens behind an opaque browser session") {
    stove {
      val rule = oidc {
        mock.whenTokenRequested(TokenRequestMatch.authorizationCode("stove-bff")) {
          subject = "alice"
          claim("name", "Alice Example")
          claim("email", "alice@example.com")
        }
      }
      val cookie = BrowserFlow(this).signIn()
      http {
        getResponse<Map<String, String>>("/api/session", headers = mapOf("Cookie" to cookie)) {
          it.status shouldBe 200
          it.body().keys shouldContainExactlyInAnyOrder listOf("subject", "csrfToken")
          it.body()["subject"] shouldBe "alice"
          it.header("Cache-Control") shouldBe "no-store"
        }
        getResponse<Map<String, String>>("/api/profile", headers = mapOf("Cookie" to cookie)) {
          it.status shouldBe 200
          it.body()["sub"] shouldBe "alice"
          it.body()["name"] shouldBe "Alice Example"
          it.body().keys.all { name -> name in setOf("sub", "name", "preferred_username", "email") } shouldBe true
        }
      }
      rule.shouldHaveBeenCalled(1)
    }
  }

  test("authorization requests contain state, nonce and S256 PKCE") {
    stove {
      val login = BrowserFlow(this).begin()
      val query = login.url.query()
      query["code_challenge_method"] shouldBe "S256"
      query.getValue("code_challenge").length shouldBe 43
      query.getValue("nonce").length shouldBe 43
      query.getValue("state").length shouldBe 43
      query["redirect_uri"] shouldBe "${StoveConfig.origin}/auth/callback"
      query["scope"] shouldBe "openid profile email orders:read"
    }
  }

  test("orders use server-held tokens and refresh behind the same browser cookie") {
    stove {
      oidc {
        mock.whenTokenRequested(TokenRequestMatch.authorizationCode("stove-bff")) {
          subject = "alice"
          audience("orders-api")
          scopes("orders:read")
          expiresIn = 3.seconds
        }
      }
      val refreshed = oidc {
        mock.whenTokenRequested(TokenRequestMatch.refreshToken("stove-bff")) {
          subject = "alice"
          audience("orders-api")
          scopes("orders:read")
        }
      }
      http { getBodilessResponse("/api/orders") { it.status shouldBe 401 } }
      val cookie = BrowserFlow(this).signIn()
      http { shouldReturnOrders(cookie, "alice") }
      delay(3500)
      http { shouldReturnOrders(cookie, "alice") }
      refreshed.shouldHaveBeenCalled(1)
    }
  }

  test("missing orders scope returns forbidden and keeps the session") {
    stove {
      oidc {
        mock.whenTokenRequested(TokenRequestMatch.authorizationCode("stove-bff")) {
          audience("orders-api")
          scopes("profile")
        }
      }
      val cookie = BrowserFlow(this).signIn()
      http {
        getBodilessResponse("/api/orders", headers = mapOf("Cookie" to cookie)) { it.status shouldBe 403 }
        getBodilessResponse("/api/session", headers = mapOf("Cookie" to cookie)) { it.status shouldBe 200 }
      }
    }
  }

  test("a wrong orders audience clears the browser cookie and closes the session") {
    stove {
      oidc {
        mock.whenTokenRequested(TokenRequestMatch.authorizationCode("stove-bff")) {
          audience("another-api")
          scopes("orders:read")
        }
      }
      val cookie = BrowserFlow(this).signIn()
      http {
        getBodilessResponse("/api/orders", headers = mapOf("Cookie" to cookie)) {
          it.status shouldBe 401
          it.header("Set-Cookie") shouldContain "Max-Age=0"
        }
        getBodilessResponse("/api/session", headers = mapOf("Cookie" to cookie)) { it.status shouldBe 401 }
      }
    }
  }

  test("callback requires the initiating browser and cannot be replayed") {
    stove {
      val browser = BrowserFlow(this)
      val login = browser.begin()
      val callback = browser.authorize(login)
      browser.callback(callback, "", 400)
      browser.callback(callback, "bff_login=another-browser", 400)
      browser.callback(callback, login.cookie)
      browser.callback(callback, login.cookie, 400)
    }
  }

  test("a changed nonce is rejected before establishing a session") {
    stove {
      val browser = BrowserFlow(this)
      val login = browser.begin()
      val callback = browser.authorize(login.withParameter("nonce", "attacker-nonce"))
      val response = browser.callback(callback, login.cookie, 400)
      response.headerValues("Set-Cookie").none { it.startsWith("bff_session=") } shouldBe true
    }
  }

  test("a changed PKCE challenge fails the provider exchange") {
    stove {
      val browser = BrowserFlow(this)
      val login = browser.begin()
      val callback = browser.authorize(login.withParameter("code_challenge", "x".repeat(43)))
      browser.callback(callback, login.cookie, 400)
    }
  }

  test("provider rejection does not create a session and consumes the login attempt") {
    stove {
      val browser = BrowserFlow(this)
      val login = browser.begin()
      val callback = URLBuilder("${StoveConfig.origin}/auth/callback").apply {
        parameters["state"] = login.url.query().getValue("state")
        parameters["code"] = "invalid-code"
      }.build()
      browser.callback(callback, login.cookie, 400)
      browser.callback(callback, login.cookie, 400)
    }
  }

  val rejectedTokens: Map<String, MockTokenBuilder.() -> Unit> = mapOf(
    "wrong issuer" to { issuer = "https://wrong.example" },
    "expired token" to { expiresIn = (-5).minutes },
    "future not-before time" to { notBefore = Instant.now().plusSeconds(300) }
  )
  for ((name, configure) in rejectedTokens) {
    test("a token with $name cannot establish a browser session") {
      stove {
        val rule = oidc { mock.whenTokenRequested(TokenRequestMatch.authorizationCode("stove-bff"), configure) }
        val browser = BrowserFlow(this)
        val login = browser.begin()
        val response = browser.callback(browser.authorize(login), login.cookie, 400)
        response.headerValues("Set-Cookie").none { it.startsWith("bff_session=") } shouldBe true
        rule.shouldHaveBeenCalled(1)
      }
    }
  }

  test("expired access tokens refresh behind the same browser cookie") {
    stove {
      oidc {
        mock.whenTokenRequested(TokenRequestMatch.authorizationCode("stove-bff")) {
          subject = "alice"
          expiresIn = 3.seconds
        }
      }
      val refreshed = oidc { mock.whenTokenRequested(TokenRequestMatch.refreshToken("stove-bff")) { subject = "alice" } }
      val cookie = BrowserFlow(this).signIn()
      delay(3500)
      http {
        getResponse<Map<String, String>>("/api/profile", headers = mapOf("Cookie" to cookie)) {
          it.status shouldBe 200
          it.body()["sub"] shouldBe "alice"
        }
      }
      refreshed.shouldHaveBeenCalled(1)
    }
  }

  test("a changed identity on refresh clears the browser cookie and closes the session") {
    stove {
      oidc {
        mock.whenTokenRequested(TokenRequestMatch.authorizationCode("stove-bff")) {
          subject = "alice"
          expiresIn = 3.seconds
        }
      }
      val refreshed = oidc { mock.whenTokenRequested(TokenRequestMatch.refreshToken("stove-bff")) { subject = "someone-else" } }
      val cookie = BrowserFlow(this).signIn()
      delay(3500)
      http {
        getBodilessResponse("/api/profile", headers = mapOf("Cookie" to cookie)) {
          it.status shouldBe 401
          it.header("Set-Cookie") shouldContain "Max-Age=0"
        }
        getBodilessResponse("/api/profile", headers = mapOf("Cookie" to cookie)) { it.status shouldBe 401 }
      }
      refreshed.shouldHaveBeenCalled(1)
    }
  }

  test("logout requires both CSRF token and matching origin, then invalidates the session") {
    stove {
      val browser = BrowserFlow(this)
      val cookie = browser.signIn()
      val session = browser.session(cookie)
      http {
        val headers = mapOf("Cookie" to cookie, "Origin" to StoveConfig.origin, "X-CSRF-Token" to session.csrfToken)
        postAndExpectBodilessResponse("/auth/logout", None, headers = headers - "X-CSRF-Token") { it.status shouldBe 403 }
        postAndExpectBodilessResponse("/auth/logout", None, headers = headers + ("Origin" to "https://another.example")) {
          it.status shouldBe 403
        }
        postAndExpectBodilessResponse("/auth/logout", None, headers = headers) {
          it.status shouldBe 204
          it.header("Set-Cookie") shouldContain "Max-Age=0"
        }
        getBodilessResponse("/api/session", headers = mapOf("Cookie" to cookie)) { it.status shouldBe 401 }
        getBodilessResponse("/api/profile", headers = mapOf("Cookie" to cookie)) { it.status shouldBe 401 }
      }
    }
  }
})
