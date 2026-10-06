package stove.ktor.bff.fixtures

import com.trendyol.stove.http.HttpSystem
import com.trendyol.stove.http.HttpSystem.Companion.client
import io.kotest.matchers.shouldBe
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.HttpStatusCode
import io.ktor.utils.io.readBuffer

suspend fun HttpSystem.shouldReturnOrdersMetadata(headers: Map<String, String>) {
  // Stove's HTTP DSL exposes GET/POST/PUT/PATCH/DELETE but no HEAD operation.
  client {
    val response = head("/orders/large") { headers.forEach { (name, value) -> header(name, value) } }
    response.status shouldBe HttpStatusCode.OK
    response.headers["Content-Length"] shouldBe "128"
    response.bodyAsText() shouldBe ""
  }
}

suspend fun HttpSystem.shouldReturnNotModifiedOrders(headers: Map<String, String>) {
  // Stove's GET helpers use eager responses. Ktor 3.6 incorrectly compares a 304 representation length to its empty body.
  client {
    prepareGet("/orders/conditional") {
      headers.forEach { (name, value) -> header(name, value) }
      header("If-None-Match", "\"v1\"")
    }.execute { response ->
      response.status shouldBe HttpStatusCode.NotModified
      response.headers["Content-Length"] shouldBe "128"
      response.headers["ETag"] shouldBe "\"v1\""
      response.bodyAsChannel().readBuffer().size shouldBe 0L
    }
  }
}
