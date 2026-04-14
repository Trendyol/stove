package com.trendyol.stove.system.abstractions

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldMatch
import io.kotest.matchers.string.shouldNotContain

private object PaymentService : SystemKey

private object OrderService : SystemKey

class SystemKeyTest :
  FunSpec({
    test("keyDisplayName returns simpleName for named objects") {
      keyDisplayName(PaymentService) shouldBe "PaymentService"
      keyDisplayName(OrderService) shouldBe "OrderService"
    }

    test("keyDisplayName sanitizes invalid filename characters") {
      val anonymousKey = object : SystemKey {}
      val name = keyDisplayName(anonymousKey)
      name shouldNotContain "<"
      name shouldNotContain ">"
      name shouldNotContain "/"
      name shouldNotContain "\\"
      name shouldMatch Regex("[a-zA-Z0-9._-]+")
    }

    test("different SystemKey objects have different classes") {
      PaymentService::class shouldNotBe OrderService::class
    }

    test("same SystemKey object always returns same class") {
      PaymentService::class shouldBe PaymentService::class
    }
  })
