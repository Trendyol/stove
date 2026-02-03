package com.trendyol.stove.system

import arrow.core.None
import com.trendyol.stove.system.abstractions.*
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking

class StoveTest :
  FunSpec({
    test("getOrRegister returns existing instance") {
      val stove = Stove()
      val system = TestLifecycleSystem(stove)

      val first = stove.getOrRegister(system)
      val second = stove.getOrRegister(system)

      first shouldBe second
    }

    test("getOrNone returns None when system missing") {
      val stove = Stove()

      stove.getOrNone<TestLifecycleSystem>() shouldBe None
    }

    test("run invokes lifecycle and passes configurations") {
      val stove = Stove()
      val system = TestLifecycleSystem(stove)
      val app = TestApplicationUnderTest()

      stove.getOrRegister(system)
      stove.applicationUnderTest(app)

      runBlocking { stove.run() }

      system.beforeRunCalled shouldBe true
      system.runCalled shouldBe true
      system.afterRunCalled shouldBe true
      app.started shouldBe true
      app.receivedConfigs shouldBe listOf("system.config=true")
      stove.applicationUnderTestContext<String>() shouldBe "context"
      Stove.instanceInitialized() shouldBe true
    }

    test("stove validation DSL throws when not initialized") {
      if (!Stove.instanceInitialized()) {
        shouldThrow<IllegalStateException> {
          runBlocking { stove { } }
        }
      } else {
        runBlocking { stove { } }
      }
    }
  })

private class TestApplicationUnderTest : ApplicationUnderTest<String> {
  var started: Boolean = false
  var receivedConfigs: List<String> = emptyList()

  override suspend fun start(configurations: List<String>): String {
    started = true
    receivedConfigs = configurations
    return "context"
  }

  override suspend fun stop() = Unit
}

private class TestLifecycleSystem(
  override val stove: Stove
) : PluggedSystem,
  BeforeRunAware,
  RunAware,
  AfterRunAware,
  ExposesConfiguration {
  var beforeRunCalled: Boolean = false
  var runCalled: Boolean = false
  var afterRunCalled: Boolean = false

  override suspend fun beforeRun() {
    beforeRunCalled = true
  }

  override suspend fun run() {
    runCalled = true
  }

  override suspend fun stop() = Unit

  override suspend fun afterRun() {
    afterRunCalled = true
  }

  override fun configuration(): List<String> = listOf("system.config=true")

  override fun then(): Stove = stove

  override fun close() = Unit
}
