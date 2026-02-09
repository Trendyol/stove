package com.trendyol.stove.ktor

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class KtorDiCheckTest :
  FunSpec({

    test("isKoinAvailable should return false when Koin is not on classpath") {
      // Koin is compileOnly, so it should not be on the test classpath
      KtorDiCheck.isKoinAvailable() shouldBe false
    }

    test("isKtorDiAvailable should return false when Ktor-DI is not on classpath") {
      // Ktor-DI is compileOnly, so it should not be on the test classpath
      KtorDiCheck.isKtorDiAvailable() shouldBe false
    }

    test("neither DI framework should be available in test classpath") {
      // Since both Koin and Ktor-DI are compileOnly dependencies,
      // neither should be detected in the test runtime classpath
      val koinAvailable = KtorDiCheck.isKoinAvailable()
      val ktorDiAvailable = KtorDiCheck.isKtorDiAvailable()

      koinAvailable shouldBe false
      ktorDiAvailable shouldBe false
    }
  })
