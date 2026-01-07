package com.trendyol.stove.system

import com.trendyol.stove.system.abstractions.*
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlin.reflect.KClass

class StoveOptionsDslTest :
  FunSpec({
    test("should keep dependencies running") {
      val stoveOptionsDsl = StoveOptionsDsl()
      stoveOptionsDsl.keepDependenciesRunning()

      stoveOptionsDsl.options.keepDependenciesRunning shouldBe true
    }

    test("should check if running locally") {
      val stoveOptionsDsl = StoveOptionsDsl()
      stoveOptionsDsl.isRunningLocally() shouldBe (
        System.getenv("CI") != "true" &&
          System.getenv("GITLAB_CI") != "true" &&
          System.getenv("GITHUB_ACTIONS") != "true"
      )
    }

    test("should enable reuse for test containers") {
      val stoveOptionsDsl = StoveOptionsDsl()
      stoveOptionsDsl.enableReuseForTestContainers()
    }

    test("should set state storage factory") {
      val stoveOptionsDsl = StoveOptionsDsl()

      class AnotherStateStorageFactory : StateStorageFactory {
        override fun <T : Any> invoke(
          options: StoveOptions,
          system: KClass<*>,
          state: KClass<T>
        ): StateStorage<T> = object : StateStorage<T> {
          override suspend fun capture(start: suspend () -> T): T = start()

          override fun isSubsequentRun(): Boolean = false
        }
      }

      data class Example1ExposedState(
        val id: Int = 1
      ) : ExposedConfiguration

      class Example1System(
        override val stove: Stove
      ) : PluggedSystem {
        override fun close() = Unit
      }

      val anotherStateStorageFactory = AnotherStateStorageFactory()
      stoveOptionsDsl.stateStorage(anotherStateStorageFactory)

      stoveOptionsDsl.options.stateStorageFactory shouldBe anotherStateStorageFactory
      val storage = stoveOptionsDsl.options.createStateStorage<Example1ExposedState, Example1System>()
      storage.isSubsequentRun() shouldBe false
      storage.capture { Example1ExposedState() } shouldBe Example1ExposedState()
    }

    test("should run migrations always") {
      val stoveOptionsDsl = StoveOptionsDsl()
      stoveOptionsDsl.runMigrationsAlways()

      stoveOptionsDsl.options.runMigrationsAlways shouldBe true
    }
  })
