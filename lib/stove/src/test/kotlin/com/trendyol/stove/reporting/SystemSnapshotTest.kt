package com.trendyol.stove.reporting

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class SystemSnapshotTest :
  FunSpec({

    test("should store system name, state, and summary") {
      val snapshot = SystemSnapshot(
        system = "Kafka",
        state = mapOf(
          "consumed" to listOf("msg1", "msg2"),
          "published" to emptyList<String>()
        ),
        summary = "Consumed: 2, Published: 0"
      )

      snapshot.system shouldBe "Kafka"
      snapshot.state["consumed"] shouldBe listOf("msg1", "msg2")
      snapshot.summary shouldBe "Consumed: 2, Published: 0"
    }

    test("should handle empty state") {
      val snapshot = SystemSnapshot(
        system = "HTTP",
        state = emptyMap(),
        summary = "No requests recorded"
      )

      snapshot.state shouldBe emptyMap()
    }

    test("should handle complex nested state") {
      val snapshot = SystemSnapshot(
        system = "WireMock",
        state = mapOf(
          "stubs" to listOf(
            mapOf("url" to "/api/users", "method" to "GET"),
            mapOf("url" to "/api/orders", "method" to "POST")
          ),
          "unmatched" to listOf(
            mapOf("url" to "/api/unknown", "count" to 3)
          )
        ),
        summary = "Stubs: 2, Unmatched: 1"
      )

      val stubs = snapshot.state["stubs"] as List<*>
      stubs.size shouldBe 2
    }

    test("should handle multiline summary") {
      val snapshot = SystemSnapshot(
        system = "PostgreSQL",
        state = mapOf("tables" to listOf("users", "orders")),
        summary = """
          |Tables: 2
          |Rows inserted: 150
          |Last query: SELECT * FROM users
        """.trimMargin()
      )

      snapshot.summary.lines().size shouldBe 3
    }
  })
