package com.trendyol.stove.extensions.kotest

import com.trendyol.stove.extensions.ReportProbe
import com.trendyol.stove.reporting.ReportEntry
import com.trendyol.stove.system.Stove
import com.trendyol.stove.system.stove
import io.kotest.core.spec.style.*
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

internal val styleReports = ReportProbe { it.specName?.endsWith("StyleTest") == true }

private suspend fun verifyStyle(vararg path: String) {
  val context = ReportProbe.assertActive(path.toList())
  stove {
    withContext(Dispatchers.Default) {
      delay(1)
      ReportProbe.assertActive(path.toList()) shouldBe context
      Stove.reporter().record(ReportEntry.success("Style", context.testId, "suspended assertion"))
    }
  }
}

class FunStyleTest : FunSpec({
  context("orders") {
    test("first") { verifyStyle("context orders", "first") }
    test("second") { verifyStyle("context orders", "second") }
  }
})

class BehaviorStyleTest : BehaviorSpec({
  given("an order") {
    `when`("submitted") {
      then("first") { verifyStyle("Given: an order", "When: submitted", "Then: first") }
      then("second") { verifyStyle("Given: an order", "When: submitted", "Then: second") }
    }
  }
})

class StringStyleTest : StringSpec({
  "first" { verifyStyle("first") }
  "second" { verifyStyle("second") }
})

class DescribeStyleTest : DescribeSpec({
  describe("orders") {
    it("first") { verifyStyle("Describe: orders", "It: first") }
    it("second") { verifyStyle("Describe: orders", "It: second") }
  }
})

class ShouldStyleTest : ShouldSpec({
  context("orders") {
    should("first") { verifyStyle("context orders", "should first") }
    should("second") { verifyStyle("context orders", "should second") }
  }
})

class ExpectStyleTest : ExpectSpec({
  context("orders") {
    expect("first") { verifyStyle("Context: orders", "Expect: first") }
    expect("second") { verifyStyle("Context: orders", "Expect: second") }
  }
})

class FeatureStyleTest : FeatureSpec({
  feature("orders") {
    scenario("first") { verifyStyle("Feature: orders", "Scenario: first") }
    scenario("second") { verifyStyle("Feature: orders", "Scenario: second") }
  }
})

class FreeStyleTest : FreeSpec({
  "orders" - {
    "first" { verifyStyle("orders", "first") }
    "second" { verifyStyle("orders", "second") }
  }
})

class WordStyleTest : WordSpec({
  "orders" should {
    "first" { verifyStyle("orders", "first") }
    "second" { verifyStyle("orders", "second") }
  }
})

class AnnotationStyleTest : AnnotationSpec() {
  @Test
  suspend fun first() = verifyStyle("first")

  @Test
  suspend fun second() = verifyStyle("second")
}
