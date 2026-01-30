package com.trendyol.stove.tracing

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldHaveLength
import io.kotest.matchers.string.shouldMatch

class TraceContextTest :
  FunSpec({

    beforeTest {
      TraceContext.clear()
    }

    afterTest {
      TraceContext.clear()
    }

    test("start should create a new TraceContext with valid IDs") {
      val ctx = TraceContext.start("test-1")

      ctx.testId shouldBe "test-1"
      ctx.traceId shouldHaveLength 32
      ctx.rootSpanId shouldHaveLength 16
      ctx.traceId shouldMatch Regex("[a-f0-9]{32}")
      ctx.rootSpanId shouldMatch Regex("[a-f0-9]{16}")
    }

    test("current should return the active context") {
      TraceContext.current().shouldBeNull()

      val ctx = TraceContext.start("test-1")

      TraceContext.current().shouldNotBeNull()
      TraceContext.current() shouldBe ctx
    }

    test("clear should remove the current context") {
      TraceContext.start("test-1")
      TraceContext.current().shouldNotBeNull()

      TraceContext.clear()

      TraceContext.current().shouldBeNull()
    }

    test("toTraceparent should generate valid W3C traceparent header") {
      val ctx = TraceContext.start("test-1")

      val traceparent = ctx.toTraceparent()

      traceparent shouldMatch Regex("00-[a-f0-9]{32}-[a-f0-9]{16}-01")
      traceparent shouldBe "00-${ctx.traceId}-${ctx.rootSpanId}-01"
    }

    test("parseTraceparent should extract traceId and spanId") {
      val traceparent = "00-abcd1234abcd1234abcd1234abcd1234-1234567890abcdef-01"

      val result = TraceContext.parseTraceparent(traceparent)

      result.shouldNotBeNull()
      result.first shouldBe "abcd1234abcd1234abcd1234abcd1234"
      result.second shouldBe "1234567890abcdef"
    }

    test("parseTraceparent should return null for invalid format") {
      val invalidTraceparent = "invalid"

      val result = TraceContext.parseTraceparent(invalidTraceparent)

      result.shouldBeNull()
    }

    test("generateTraceId should produce unique IDs") {
      val ids = (1..100).map { TraceContext.generateTraceId() }.toSet()

      ids.size shouldBe 100
    }

    test("generateSpanId should produce unique IDs") {
      val ids = (1..100).map { TraceContext.generateSpanId() }.toSet()

      ids.size shouldBe 100
    }

    test("sanitizeToAscii should sanitize Turkish characters") {
      val input = "ProductCreateCodeValidationTests::Geçerli, benzersiz code ile ürün oluşturma (happy-path)"

      val sanitized = TraceContext.sanitizeToAscii(input)

      sanitized shouldBe "ProductCreateCodeValidationTests::Gecerli, benzersiz code ile urun olusturma (happy-path)"
      // Verify all characters are ASCII printable
      sanitized.all { it.code in 0x20..0x7E } shouldBe true
    }

    test("sanitizeToAscii should handle various non-ASCII characters") {
      val input = "Test::äöü ñ café résumé naïve"

      val sanitized = TraceContext.sanitizeToAscii(input)

      sanitized shouldBe "Test::aou n cafe resume naive"
      sanitized.all { it.code in 0x20..0x7E } shouldBe true
    }

    test("sanitizeToAscii should preserve ASCII characters") {
      val input = "SimpleTest::simple test name 123"

      val sanitized = TraceContext.sanitizeToAscii(input)

      sanitized shouldBe "SimpleTest::simple test name 123"
    }

    test("sanitizeToAscii should handle Japanese characters with hash for uniqueness") {
      val input1 = "MyTest::日本語テスト"
      val input2 = "MyTest::別のテスト"

      val sanitized1 = TraceContext.sanitizeToAscii(input1)
      val sanitized2 = TraceContext.sanitizeToAscii(input2)

      // Japanese characters become underscores, but hash suffix ensures uniqueness
      sanitized1.all { it.code in 0x20..0x7E } shouldBe true
      sanitized2.all { it.code in 0x20..0x7E } shouldBe true
      // Different inputs should produce different outputs
      sanitized1 shouldNotBe sanitized2
      // Should contain hash suffix (underscore followed by hex chars)
      sanitized1 shouldMatch Regex("MyTest::_______.+")
    }

    test("sanitizeToAscii should handle mixed scripts with hash") {
      val input = "Test::Hello世界Test"

      val sanitized = TraceContext.sanitizeToAscii(input)

      // Contains non-decomposable chars, so hash is added
      sanitized.all { it.code in 0x20..0x7E } shouldBe true
      sanitized shouldMatch Regex("Test::Hello__Test_.+")
    }
  })
