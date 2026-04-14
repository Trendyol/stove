package com.trendyol.stove.system

import arrow.core.None
import arrow.core.Some
import com.trendyol.stove.system.abstractions.*
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import kotlinx.coroutines.runBlocking

private object KeyA : SystemKey

private object KeyB : SystemKey

class KeyedSystemTest :
  FunSpec({
    test("keyed getOrRegister stores and returns system") {
      val stove = Stove()
      val system = KeyedTestSystem(stove)

      val registered = stove.getOrRegister(KeyA, system)

      registered shouldBeSameInstanceAs system
    }

    test("keyed getOrRegister returns existing instance for same key") {
      val stove = Stove()
      val system1 = KeyedTestSystem(stove)
      val system2 = KeyedTestSystem(stove)

      val first = stove.getOrRegister(KeyA, system1)
      val second = stove.getOrRegister(KeyA, system2)

      first shouldBeSameInstanceAs second
      first shouldBeSameInstanceAs system1
    }

    test("different keys for same type store different instances") {
      val stove = Stove()
      val systemA = KeyedTestSystem(stove)
      val systemB = KeyedTestSystem(stove)

      val registeredA = stove.getOrRegister(KeyA, systemA)
      val registeredB = stove.getOrRegister(KeyB, systemB)

      registeredA shouldBeSameInstanceAs systemA
      registeredB shouldBeSameInstanceAs systemB
      registeredA shouldBe systemA
      registeredB shouldBe systemB
    }

    test("keyed getOrNone returns None when not registered") {
      val stove = Stove()

      stove.getOrNone<KeyedTestSystem>(KeyA) shouldBe None
    }

    test("keyed getOrNone returns Some when registered") {
      val stove = Stove()
      val system = KeyedTestSystem(stove)
      stove.getOrRegister(KeyA, system)

      val result = stove.getOrNone<KeyedTestSystem>(KeyA)

      result shouldBe Some(system)
    }

    test("keyed and default systems coexist independently") {
      val stove = Stove()
      val defaultSystem = KeyedTestSystem(stove)
      val keyedSystem = KeyedTestSystem(stove)

      stove.getOrRegister(defaultSystem)
      stove.getOrRegister(KeyA, keyedSystem)

      stove.getOrNone<KeyedTestSystem>() shouldBe Some(defaultSystem)
      stove.getOrNone<KeyedTestSystem>(KeyA) shouldBe Some(keyedSystem)
    }

    test("allRegisteredSystems includes both default and keyed systems") {
      val stove = Stove()
      val defaultSystem = KeyedTestSystem(stove)
      val keyedSystemA = KeyedTestSystem(stove)
      val keyedSystemB = KeyedTestSystem(stove)

      stove.getOrRegister(defaultSystem)
      stove.getOrRegister(KeyA, keyedSystemA)
      stove.getOrRegister(KeyB, keyedSystemB)

      val all = stove.allRegisteredSystems()
      all shouldHaveSize 3
      all.shouldContainAll(defaultSystem, keyedSystemA, keyedSystemB)
    }

    test("systemsOf returns matching systems from both maps") {
      val stove = Stove()
      val defaultSystem = KeyedLifecycleSystem(stove)
      val keyedSystem = KeyedLifecycleSystem(stove)

      stove.getOrRegister(defaultSystem)
      stove.getOrRegister(KeyA, keyedSystem)

      val runAwareSystems = stove.systemsOf<RunAware>()
      runAwareSystems shouldHaveSize 2
    }

    test("allSystems returns all registered systems") {
      val stove = Stove()
      val system1 = KeyedTestSystem(stove)
      val system2 = KeyedTestSystem(stove)

      stove.getOrRegister(system1)
      stove.getOrRegister(KeyA, system2)

      stove.allSystems() shouldHaveSize 2
    }

    test("keyed systems participate in run lifecycle") {
      val stove = Stove()
      val defaultSystem = KeyedLifecycleSystem(stove)
      val keyedSystem = KeyedLifecycleSystem(stove)
      val app = KeyedTestApp()

      stove.getOrRegister(defaultSystem)
      stove.getOrRegister(KeyA, keyedSystem)
      stove.applicationUnderTest(app)

      runBlocking { stove.run() }

      defaultSystem.beforeRunCalled shouldBe true
      defaultSystem.runCalled shouldBe true
      defaultSystem.afterRunCalled shouldBe true

      keyedSystem.beforeRunCalled shouldBe true
      keyedSystem.runCalled shouldBe true
      keyedSystem.afterRunCalled shouldBe true

      app.started shouldBe true
      app.receivedConfigs shouldHaveSize 2
    }

    test("keyed systems contribute configurations") {
      val stove = Stove()
      val defaultSystem = KeyedLifecycleSystem(stove, configValue = "default.config=true")
      val keyedSystem = KeyedLifecycleSystem(stove, configValue = "keyed.config=true")
      val app = KeyedTestApp()

      stove.getOrRegister(defaultSystem)
      stove.getOrRegister(KeyA, keyedSystem)
      stove.applicationUnderTest(app)

      runBlocking { stove.run() }

      app.receivedConfigs.shouldContainAll("default.config=true", "keyed.config=true")
    }
  })

private class KeyedTestSystem(
  override val stove: Stove
) : PluggedSystem {
  override fun then(): Stove = stove

  override fun close() = Unit
}

private class KeyedTestApp : ApplicationUnderTest<String> {
  var started: Boolean = false
  var receivedConfigs: List<String> = emptyList()

  override suspend fun start(configurations: List<String>): String {
    started = true
    receivedConfigs = configurations
    return "context"
  }

  override suspend fun stop() = Unit
}

private class KeyedLifecycleSystem(
  override val stove: Stove,
  private val configValue: String = "system.config=true"
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

  override fun configuration(): List<String> = listOf(configValue)

  override fun then(): Stove = stove

  override fun close() = Unit
}
