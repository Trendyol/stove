package com.trendyol.stove.reporting

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class ConsoleTextWrapperTest :
  FunSpec({
    test("wraps at a nearby delimiter instead of splitting a word at the width") {
      val wrapped = ConsoleTextWrapper.wrap("Input: alpha beta gamma", width = 16)

      wrapped.lines().first() shouldBe "Input: alpha"
    }
  })
