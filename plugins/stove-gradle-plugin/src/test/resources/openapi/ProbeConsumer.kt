package fixture

import com.trendyol.stove.system.*
import com.trendyol.stove.system.abstractions.*
import com.trendyol.stove.wiremock.*
import java.net.URI
import java.net.http.*
import java.time.OffsetDateTime
import kotlinx.coroutines.runBlocking
import stove.generated.wiremock.supplier.*

data class Item(val value: String)
object Supplier : SystemKey
enum class Channel { online, shop }

fun main() = runBlocking {
  lateinit var url: String
  Stove().with {
    wiremock(Supplier) { WireMockSystemOptions { url = it.baseUrl; emptyList() } }
    applicationUnderTest(object : ApplicationUnderTest<Unit> {
      override suspend fun start(configurations: List<String>) = Unit
      override suspend fun stop() = Unit
    })
  }.run()
  val client = HttpClient.newHttpClient()
  fun send(path: String, body: String? = null, vararg headers: String) = client.send(
    HttpRequest.newBuilder(URI("$url$path")).apply {
      if (headers.isNotEmpty()) headers(*headers)
      if (body != null) header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body))
    }.build(),
    HttpResponse.BodyHandlers.ofString(),
  )
  fun expect(label: String, actual: Any?, expected: Any?) = check(actual == expected) { "$label: expected $expected, got $actual" }
  try {
    stove {
      wiremock(Supplier) {
        supplier.createNote(body = Note(text = "a")) { created(Note(text = "a")) }
        supplier.createNote(body = Note(text = "b", at = OffsetDateTime.parse("2024-01-01T10:00Z"))) {
          created(Note(text = "b", at = OffsetDateTime.parse("2024-01-01T10:00Z")))
        }
        supplier.mixed { internalServerError("boom") }
        supplier.multi(body = Note(text = "m")) { noContent() }
        supplier.secure { noContent() }
      }
    }
    expect("null field", send("/notes", """{"text":"a","note":null}""").statusCode(), 201)
    expect("absent field", send("/notes", """{"text":"a"}""").statusCode(), 201)
    expect("all nulls", send("/notes", """{"text":"a","note":null,"at":null}""").statusCode(), 201)
    expect("unknown field", send("/notes", """{"text":"a","zzz":1}""").statusCode(), 404)
    val dated = send("/notes", """{"text":"b","at":"2024-01-01T10:00:00Z"}""")
    expect("date call", dated.statusCode(), 201)
    expect("date body", dated.body().contains("\"2024-01-01T10:00:00Z\""), true)
    val mixed = send("/mixed")
    expect("text response", mixed.statusCode() to mixed.body(), 500 to "boom")
    expect("json of many media types", send("/multi", """{"text":"m"}""").statusCode(), 204)
    expect("reserved headers", send("/secure", null, "Authorization", "Bearer x", "Accept", "*/*").statusCode(), 204)
    println("PROBE passed")
  } finally { Stove.stop() }
}
