package com.trendyol.stove.ktor

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.server.plugins.di.*
import org.koin.core.context.stopKoin
import kotlin.reflect.typeOf

class AutoDetectRuntimeStateTest :
  FunSpec({
    test("autoDetect prefers Ktor-DI when both Koin and Ktor-DI are active") {
      val application = BothActiveDiTestApp.run(emptyArray())
      application.attributes.contains(DependencyRegistryKey) shouldBe true

      val resolver = DependencyResolvers.autoDetect()

      val resolvedService = resolver(application, typeOf<ExampleService>()) as ExampleService
      resolvedService.whatIsTheTime() shouldBe GetUtcNow.frozenTime

      val resolvedConfig = resolver(application, typeOf<TestConfig>()) as TestConfig
      resolvedConfig.message shouldBe "Hello from Stove!"

      val resolvedPaymentServices = resolver(application, typeOf<List<PaymentService>>())
      (resolvedPaymentServices as List<*>)
        .filterIsInstance<PaymentService>()
        .map { it.providerName } shouldContainExactlyInAnyOrder listOf("Stripe", "PayPal", "Square")
    }

    test("autoDetect throws clear error when no runtime DI is active") {
      runCatching { stopKoin() }
      val application = NoDiTestApp.run(emptyArray())
      application.attributes.contains(DependencyRegistryKey) shouldBe false

      val resolver = DependencyResolvers.autoDetect()
      val error = shouldThrow<IllegalStateException> {
        resolver(application, typeOf<ExampleService>())
      }

      error.message shouldContain "No active DI framework detected"
      error.message shouldContain "install(Koin)"
      error.message shouldContain "dependencies { ... }"
    }
  })
