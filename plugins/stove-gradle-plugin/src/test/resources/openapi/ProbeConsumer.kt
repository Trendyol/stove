package fixture

import com.fasterxml.jackson.annotation.JsonInclude
import com.github.tomakehurst.wiremock.client.WireMock.exactly
import com.trendyol.stove.serialization.StoveSerde
import com.trendyol.stove.system.*
import com.trendyol.stove.system.abstractions.*
import com.trendyol.stove.wiremock.*
import java.net.URI
import java.net.http.*
import java.time.Instant
import java.time.OffsetDateTime
import kotlinx.coroutines.runBlocking
import stove.generated.wiremock.supplier.*

@JsonInclude(JsonInclude.Include.NON_NULL)
data class Item(val name: String, val createdAt: Instant, val detail: String? = null)
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
  fun send(path: String, body: String? = null, vararg headers: String, method: String = if (body == null) "GET" else "POST") = client.send(
    HttpRequest.newBuilder(URI("$url$path")).apply {
      if (headers.isNotEmpty()) headers(*headers)
      if (body != null) header("Content-Type", "application/json")
      method(method, body?.let(HttpRequest.BodyPublishers::ofString) ?: HttpRequest.BodyPublishers.noBody())
    }.build(),
    HttpResponse.BodyHandlers.ofString(),
  )
  fun expect(label: String, actual: Any?, expected: Any?) = check(actual == expected) { "$label: expected $expected, got $actual" }
  val timestamp = "2024-01-01T10:00:00Z"
  val item = Item("some item", Instant.parse(timestamp))
  try {
    stove {
      wiremock(Supplier) {
        supplier.createNote(body = Note(text = "a")) { created(Note(text = "a")) }
        supplier.createNote(body = Note(text = "b", at = OffsetDateTime.parse("2024-01-01T10:00Z"))) {
          created(Note(text = "b", at = OffsetDateTime.parse("2024-01-01T10:00Z")))
        }
        supplier.mixed { internalServerError("boom") }
        supplier.multi(body = item) { created(item) }
        supplier.nullableBody(mode = "strict", body = null) { noContent() }
        supplier.nullableBody(mode = "any") { accepted() }
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
    val itemJson = """{"name":"some item","createdAt":"$timestamp"}"""
    val mapped = send("/multi", itemJson)
    expect("mapped item matches the JSON media type", mapped.statusCode(), 201)
    val json = StoveSerde.jackson.default
    expect("typed response preserves the Instant and excludes null fields", json.readTree(mapped.body()), json.readTree(itemJson))
    expect("explicit null is extra on a NON_NULL DTO", send("/multi", """${itemJson.dropLast(1)},"detail":null}""").statusCode(), 404)
    expect("a value is extra on a NON_NULL DTO", send("/multi", """${itemJson.dropLast(1)},"detail":"unexpected"}""").statusCode(), 404)
    expect("literal JSON null", send("/nullable-body?mode=strict", "null").statusCode(), 204)
    expect("absent body is different from JSON null", send("/nullable-body?mode=strict", method = "POST").statusCode(), 404)
    expect("a value is different from JSON null", send("/nullable-body?mode=strict", "\"value\"").statusCode(), 404)
    expect("omitting the matcher argument permits a value", send("/nullable-body?mode=any", "\"value\"").statusCode(), 202)
    stove {
      wiremock(Supplier) {
        shouldHaveBeenCalled(supplier.requests.multi(body = item), exactly(1))
        shouldHaveBeenCalled(supplier.requests.nullableBody(mode = "strict", body = null), exactly(1))
      }
    }
    expect("reserved headers", send("/secure", null, "Authorization", "Bearer x", "Accept", "*/*").statusCode(), 204)
    println("PROBE passed")
  } finally { Stove.stop() }
}
