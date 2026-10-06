package stove.ktor.bff.fixtures

import com.trendyol.stove.http.HttpSystem
import io.kotest.matchers.shouldBe

fun expectedOrders(subject: String): Map<String, Any> = mapOf(
  "subject" to subject,
  "orders" to listOf(mapOf("id" to "order-1001", "status" to "confirmed"))
)

suspend fun HttpSystem.shouldReturnOrders(cookie: String, subject: String) {
  getResponse<Map<String, Any>>("/api/orders", headers = mapOf("Cookie" to cookie)) {
    it.status shouldBe 200
    it.body() shouldBe expectedOrders(subject)
    it.header("Cache-Control") shouldBe "no-store"
  }
}
