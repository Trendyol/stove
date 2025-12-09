package com.trendyol.stove.testing.e2e.ktor

import com.trendyol.stove.testing.e2e.system.TestSystem.Companion.validate
import com.trendyol.stove.testing.e2e.system.using
import io.kotest.core.config.AbstractProjectConfig
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import java.math.BigDecimal

/**
 * Shared bridge system tests that work with both Koin and Ktor-DI.
 * Each DI variant module only needs to provide the Stove setup configuration.
 */
abstract class BridgeSystemTests(
  private val stoveSetup: AbstractProjectConfig
) : ShouldSpec({
    beforeSpec {
      stoveSetup.beforeProject()
    }

    afterSpec {
      stoveSetup.afterProject()
    }

    should("resolve service from DI container") {
      validate {
        using<ExampleService> {
          whatIsTheTime() shouldBe GetUtcNow.frozenTime
        }
      }
    }

    should("resolve multiple dependencies") {
      validate {
        using<GetUtcNow, ExampleService> { getUtcNow, exampleService ->
          getUtcNow() shouldBe GetUtcNow.frozenTime
          exampleService.whatIsTheTime() shouldBe GetUtcNow.frozenTime
        }
      }
    }

    should("resolve config from DI container") {
      validate {
        using<TestConfig> {
          message shouldBe "Hello from Stove!"
        }
      }
    }

    should("resolve multiple instances of same interface") {
      validate {
        using<List<PaymentService>> {
          val order = Order("order-123", BigDecimal("99.99"))
          val results = map { it.pay(order) }

          results.map { it.provider } shouldContainExactlyInAnyOrder listOf("Stripe", "PayPal", "Square")
          results.all { it.success } shouldBe true
        }
      }
    }
  })
