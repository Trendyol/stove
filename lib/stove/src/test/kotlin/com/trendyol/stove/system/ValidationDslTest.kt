package com.trendyol.stove.system

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class ValidationDslTest :
  FunSpec({
    test("should expose stove instance") {
      val stove = Stove()
      val dsl = ValidationDsl(stove)

      dsl.stove shouldBe stove
    }
  })
