package com.trendyol.stove.interactions

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class MockInteractionTest :
  FunSpec({
    test("sensitive JSON fields are redacted recursively") {
      MockInteraction.redactSensitiveBody(
        """{"user":"ada","password":"secret","nested":{"access_token":"token"},"items":[{"api_key":"key"}]}"""
      ) shouldBe
        """{"user":"ada","password":"<redacted>","nested":{"access_token":"<redacted>"},"items":[{"api_key":"<redacted>"}]}"""
    }

    test("unstructured bodies are redacted unless raw capture is explicitly enabled") {
      MockInteraction.capturedBody(
        body = "secret=plain-text",
        retainRawBody = false,
        redactor = MockInteraction::redactSensitiveBody
      ) shouldBe (MockInteraction.REDACTED_BODY to false)

      MockInteraction.capturedBody(
        body = "secret=plain-text",
        retainRawBody = true,
        redactor = MockInteraction::redactSensitiveBody
      ) shouldBe ("secret=plain-text" to false)
    }

    test("redaction happens before body truncation") {
      MockInteraction.capturedBody(
        body = "raw",
        retainRawBody = false,
        redactor = { "x".repeat(MockInteraction.MAX_BODY_CHARS + 1) }
      ) shouldBe ("x".repeat(MockInteraction.MAX_BODY_CHARS) to true)
    }
  })
