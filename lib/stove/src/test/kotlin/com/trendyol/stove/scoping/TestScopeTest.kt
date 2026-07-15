package com.trendyol.stove.scoping

import com.trendyol.stove.interactions.InteractionAttribution
import com.trendyol.stove.interactions.resolveAttribution
import com.trendyol.stove.interactions.traceparentTraceId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

class TestScopeTest :
  FunSpec({
    test("test id source distinguishes header from baggage") {
      mapOf("X-Stove-Test-Id" to "t-1").stoveTestIdWithSource() shouldBe ("t-1" to TestIdSource.HEADER)
      mapOf("baggage" to "stove.test.id=t-2").stoveTestIdWithSource() shouldBe ("t-2" to TestIdSource.BAGGAGE)
      // The explicit header wins over baggage when both are present.
      mapOf(
        "x-stove-test-id" to "t-1",
        "baggage" to "stove.test.id=t-2"
      ).stoveTestIdWithSource() shouldBe ("t-1" to TestIdSource.HEADER)
      emptyMap<String, String>().stoveTestIdWithSource().shouldBeNull()
    }

    test("attribution resolution is proven-only") {
      resolveAttribution(mapOf("X-Stove-Test-Id" to "t-1"), matchedStubTestId = null) shouldBe
        ("t-1" to InteractionAttribution.PROVEN_HEADER)
      resolveAttribution(mapOf("baggage" to "stove.test.id=t-2"), matchedStubTestId = null) shouldBe
        ("t-2" to InteractionAttribution.PROVEN_BAGGAGE)
      // Traffic evidence wins over the fixture tag.
      resolveAttribution(mapOf("X-Stove-Test-Id" to "t-1"), matchedStubTestId = "t-stub") shouldBe
        ("t-1" to InteractionAttribution.PROVEN_HEADER)
      resolveAttribution(emptyMap<String, String>(), matchedStubTestId = "t-stub") shouldBe
        ("t-stub" to InteractionAttribution.PROVEN_STUB)
      resolveAttribution(emptyMap<String, String>(), matchedStubTestId = null) shouldBe
        (null to InteractionAttribution.UNATTRIBUTED)
    }

    test("trace id is extracted from a well-formed traceparent only") {
      mapOf("traceparent" to "00-29326663e1ca49e89820727aa7955f2b-6c1e47ceae684b55-01")
        .traceparentTraceId() shouldBe "29326663e1ca49e89820727aa7955f2b"
      mapOf("traceparent" to "garbage").traceparentTraceId().shouldBeNull()
      mapOf("traceparent" to "00-${"0".repeat(32)}-6c1e47ceae684b55-01").traceparentTraceId().shouldBeNull()
      emptyMap<String, String>().traceparentTraceId().shouldBeNull()
    }
  })
