package com.trendyol.stove.wiremock

import com.github.benmanes.caffeine.cache.Caffeine
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class ExtensionsTest :
  FunSpec({
    test("containsKey should reflect cache contents") {
      val cache = Caffeine.newBuilder().build<String, String>()

      cache.containsKey("missing") shouldBe false

      cache.put("key", "value")
      cache.containsKey("key") shouldBe true
    }
  })
