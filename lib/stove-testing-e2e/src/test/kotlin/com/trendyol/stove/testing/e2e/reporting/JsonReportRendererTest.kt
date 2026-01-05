package com.trendyol.stove.testing.e2e.reporting

import com.fasterxml.jackson.databind.ObjectMapper
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.time.Instant

class JsonReportRendererTest :
  FunSpec({

    test("generates valid JSON with entries and summary") {
      val report = TestReport("test-1", "should process order")
      report.record(ActionEntry(Instant.now(), "HTTP", "test-1", "POST /api"))
      report.record(AssertionEntry(Instant.now(), "HTTP", "test-1", "status", result = AssertionResult.PASSED))

      val json = JsonReportRenderer.render(report, emptyList())
      val parsed = ObjectMapper().readTree(json)

      parsed["testId"].asText() shouldBe "test-1"
      parsed["testName"].asText() shouldBe "should process order"
      parsed["entries"].size() shouldBe 2
      parsed["summary"]["totalActions"].asInt() shouldBe 1
      parsed["summary"]["totalAssertions"].asInt() shouldBe 1
      parsed["summary"]["passedAssertions"].asInt() shouldBe 1
    }

    test("includes system snapshots") {
      val report = TestReport("test-1", "test")
      val snapshot = SystemSnapshot(
        system = "Kafka",
        state = mapOf("consumed" to listOf(mapOf("topic" to "orders"))),
        summary = "1 message"
      )

      val json = JsonReportRenderer.render(report, listOf(snapshot))
      val parsed = ObjectMapper().readTree(json)

      parsed["systemSnapshots"]["Kafka"]["consumed"].size() shouldBe 1
      parsed["systemSnapshots"]["Kafka"]["consumed"][0]["topic"].asText() shouldBe "orders"
    }
  })
