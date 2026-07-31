package com.trendyol.stove.reporting

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class ConsoleTextWrapperTest :
  FunSpec({
    test("wraps at a nearby delimiter instead of splitting a word at the width") {
      val wrapped = ConsoleTextWrapper.wrap("Input: alpha beta gamma", width = 16)

      wrapped.lines().first() shouldBe "Input: alpha"
    }

    test("wraps when the continuation indent exceeds half the width") {
      val width = 66
      val continuationIndent = 34
      val text =
        "      abcdefghijklmnopqrstuvwxyz: " +
          (1..12).joinToString(" ") { "value-$it" }

      val lines = ConsoleTextWrapper.wrap(text, width).lines()

      (lines.size > 1) shouldBe true
      lines.all { ConsoleTextWrapper.visibleLength(it) <= width } shouldBe true
      lines.drop(1).all { it.startsWith(" ".repeat(continuationIndent)) } shouldBe true
    }
  })
