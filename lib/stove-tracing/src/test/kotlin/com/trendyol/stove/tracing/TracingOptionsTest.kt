package com.trendyol.stove.tracing

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlin.time.Duration.Companion.seconds

class TracingOptionsTest :
  FunSpec({
    test("should configure tracing options") {
      val options = TracingOptions()
        .enabled()
        .spanCollectionTimeout(2.seconds)
        .spanFilter { it.operationName == "op" }
        .maxSpansPerTrace(99)
        .enableSpanReceiver(port = 5555)

      options.enabled shouldBe true
      options.spanCollectionTimeout shouldBe 2.seconds
      options.maxSpansPerTrace shouldBe 99
      options.spanReceiverEnabled shouldBe true
      options.spanReceiverPort shouldBe 5555
      options.spanFilter(SpanInfo("t", "s", null, "op", "svc", 0, 1, SpanStatus.OK)) shouldBe true
    }

    test("copy should duplicate current values") {
      val original = TracingOptions()
        .enabled()
        .spanCollectionTimeout(3.seconds)
        .maxSpansPerTrace(7)
        .enableSpanReceiver(port = 7777)

      val copy = original.copy()

      copy.enabled shouldBe true
      copy.spanCollectionTimeout shouldBe 3.seconds
      copy.maxSpansPerTrace shouldBe 7
      copy.spanReceiverEnabled shouldBe true
      copy.spanReceiverPort shouldBe 7777
    }
  })
