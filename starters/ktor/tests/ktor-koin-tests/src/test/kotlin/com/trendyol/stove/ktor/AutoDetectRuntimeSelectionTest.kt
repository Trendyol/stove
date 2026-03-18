package com.trendyol.stove.ktor

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.server.plugins.di.*
import kotlin.reflect.typeOf

class AutoDetectRuntimeSelectionTest :
  FunSpec({
    test("autoDetect uses active Koin when Ktor-DI is only on classpath") {
      val application = KoinTestApp.run(emptyArray())
      application.attributes.contains(DependencyRegistryKey) shouldBe false

      val resolver = DependencyResolvers.autoDetect()
      val resolvedService = resolver(application, typeOf<ExampleService>()) as ExampleService

      resolvedService.whatIsTheTime() shouldBe GetUtcNow.frozenTime
    }
  })
