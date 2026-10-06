package stove.ktor.bff.fixtures

import io.ktor.client.HttpClient
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.json.*

/** Browser requests against Ktor's test host; provider authorization still uses Stove's managed HTTP system. */
class TestBrowser(private val http: HttpClient) {
  suspend fun begin(): LoginRedirect {
    val response = http.get("/auth/login")
    check(response.status == HttpStatusCode.Found)
    return LoginRedirect(Url(checkNotNull(response.headers[HttpHeaders.Location])), response.cookie("bff_login"))
  }

  suspend fun complete(login: LoginRedirect, callback: Url): String {
    val response = http.get(callback.fullPath) { header(HttpHeaders.Cookie, login.cookie) }
    check(response.status == HttpStatusCode.Found)
    return response.cookie("bff_session")
  }

  suspend fun session(cookie: String): BrowserSessionDetails {
    val response = http.get("/api/session") { header(HttpHeaders.Cookie, cookie) }
    check(response.status == HttpStatusCode.OK)
    val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
    return BrowserSessionDetails(body.getValue("subject").jsonPrimitive.content, body.getValue("csrfToken").jsonPrimitive.content)
  }
}

private fun HttpResponse.cookie(name: String): String =
  headers.getAll(HttpHeaders.SetCookie).orEmpty().single { it.startsWith("$name=") }.substringBefore(';')
