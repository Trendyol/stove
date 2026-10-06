package stove.ktor.bff.fixtures

import com.trendyol.stove.http.*
import com.trendyol.stove.http.HttpSystem.Companion.client
import com.trendyol.stove.system.ValidationDsl
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.header
import io.ktor.http.*
import stove.ktor.bff.IdentityProvider
import stove.ktor.bff.StoveConfig

/** Explicit redirects and cookies keep each simulated browser isolated and each request in Stove's report. */
class BrowserFlow(private val test: ValidationDsl) {
  suspend fun begin(): LoginRedirect {
    lateinit var redirect: LoginRedirect
    test.http {
      getBodilessResponse("/auth/login") { response ->
        response.status shouldBe 302
        val cookie = response.header("Set-Cookie")
        cookie shouldContain "HttpOnly"
        cookie shouldContain "SameSite=Lax"
        redirect = LoginRedirect(Url(response.header("Location")), cookie.substringBefore(';'))
      }
    }
    return redirect
  }

  suspend fun authorize(login: LoginRedirect): Url {
    if (StoveConfig.keycloak) return authorizeKeycloak(login)
    lateinit var callback: Url
    test.http(IdentityProvider) {
      getBodilessResponse(login.url.encodedPath, queryParams = login.url.query()) { response ->
        response.status shouldBe 302
        callback = Url(response.header("Location"))
      }
    }
    return callback
  }

  private suspend fun authorizeKeycloak(login: LoginRedirect): Url {
    lateinit var action: Url
    lateinit var cookies: String
    lateinit var callback: Url
    test.http(IdentityProvider) {
      getResponse<String>(login.url.encodedPath, queryParams = login.url.query()) { response ->
        response.status shouldBe 200
        val form = Regex("action=\"([^\"]+)\"").find(response.body()) ?: error("Missing Keycloak login form")
        action = Url(form.groupValues[1].replace("&amp;", "&"))
        cookies = response.headerValues("Set-Cookie").joinToString("; ") { it.substringBefore(';') }
      }
      // Stove's POST DSL has no query-parameter argument; Keycloak requires session_code in the form action URL.
      client {
        val response = submitForm(
          action.toString(),
          parameters {
            append("username", "alice")
            append("password", "alice")
          }
        ) {
          header("Cookie", cookies)
        }
        response.status.value shouldBe 302
        callback = Url(checkNotNull(response.headers["Location"]))
      }
    }
    return callback
  }

  suspend fun callback(url: Url, cookie: String, expectedStatus: Int = 302): StoveHttpResponse.Bodiless {
    lateinit var result: StoveHttpResponse.Bodiless
    test.http {
      getBodilessResponse(url.encodedPath, queryParams = url.query(), headers = mapOf("Cookie" to cookie)) {
        it.status shouldBe expectedStatus
        result = it
      }
    }
    return result
  }

  suspend fun signIn(configure: (LoginRedirect) -> LoginRedirect = { it }): String {
    val login = configure(begin())
    val response = callback(authorize(login), login.cookie)
    response.header("Location") shouldBe "/"
    val session = response.headerValues("Set-Cookie").single { it.startsWith("bff_session=") }
    session shouldContain "HttpOnly"
    session shouldContain "SameSite=Lax"
    return session.substringBefore(';')
  }

  suspend fun session(cookie: String): BrowserSessionDetails {
    lateinit var session: BrowserSessionDetails
    test.http {
      getResponse<Map<String, String>>("/api/session", headers = mapOf("Cookie" to cookie)) {
        it.status shouldBe 200
        val body = it.body()
        body.keys shouldBe setOf("subject", "csrfToken")
        session = BrowserSessionDetails(body.getValue("subject"), body.getValue("csrfToken"))
      }
    }
    return session
  }
}

data class BrowserSessionDetails(val subject: String, val csrfToken: String)

data class LoginRedirect(val url: Url, val cookie: String) {
  fun withParameter(name: String, value: String): LoginRedirect =
    copy(url = URLBuilder(url).apply { parameters[name] = value }.build())
}

fun Url.query(): Map<String, String> = parameters.entries().associate { it.key to it.value.single() }
fun StoveHttpResponse.headerValues(name: String): List<String> =
  (headers.entries.single { it.key.equals(name, ignoreCase = true) }.value as List<*>).map { it.toString() }
fun StoveHttpResponse.header(name: String): String = headerValues(name).first()
