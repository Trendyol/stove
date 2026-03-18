package com.trendyol.stove.ktor

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.server.application.Application
import io.ktor.util.AttributeKey
import kotlin.reflect.typeOf

class AutoDetectRuntimeSelectionTest :
  FunSpec({
    test("autoDetect uses active Koin for a Koin-only app") {
      val application = KoinTestApp.run(emptyArray())
      KtorDiCheck.isKoinAvailable() shouldBe true
      KtorDiCheck.isKtorDiAvailable() shouldBe true
      isKtorDiRegistryInstalled(application) shouldBe false

      val resolver = DependencyResolvers.autoDetect()
      val resolvedService = resolver(application, typeOf<ExampleService>()) as ExampleService

      resolvedService.whatIsTheTime() shouldBe GetUtcNow.frozenTime
    }
  })

private fun isKtorDiRegistryInstalled(application: Application): Boolean = runCatching {
  val dependencyInjectionKt = Class.forName("io.ktor.server.plugins.di.DependencyInjectionKt")
  val key = dependencyInjectionKt.getMethod("getDependencyRegistryKey").invoke(null) as AttributeKey<*>
  application.attributes.contains(key)
}.getOrDefault(false)
