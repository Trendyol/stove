package com.trendyol.stove.reporting

import com.fasterxml.jackson.databind.ObjectMapper
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class JsonReportRendererTest :
  FunSpec({

    test("generates valid JSON with entries and summary") {
      val report = TestReport("test-1", "should process order")
      report.record(ReportEntry.success("HTTP", "test-1", "POST /api"))
      report.record(ReportEntry.action("HTTP", "test-1", "status check", passed = true))

      val json = JsonReportRenderer.render(report, emptyList())
      val parsed = ObjectMapper().readTree(json)

      parsed["testId"].asText() shouldBe "test-1"
      parsed["testName"].asText() shouldBe "should process order"
      parsed["entries"].size() shouldBe 2
      parsed["summary"]["total"].asInt() shouldBe 2
      parsed["summary"]["passed"].asInt() shouldBe 2
      parsed["summary"]["failed"].asInt() shouldBe 0
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
