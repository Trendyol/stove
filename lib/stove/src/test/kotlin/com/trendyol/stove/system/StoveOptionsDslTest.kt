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

    test("should enable reporting") {
      val stoveOptionsDsl = StoveOptionsDsl()
      stoveOptionsDsl.reportingEnabled(true)

      stoveOptionsDsl.options.reportingEnabled shouldBe true
    }

    test("should disable reporting") {
      val stoveOptionsDsl = StoveOptionsDsl()
      stoveOptionsDsl.reportingEnabled(false)

      stoveOptionsDsl.options.reportingEnabled shouldBe false
    }

    test("should enable dump report on test failure") {
      val stoveOptionsDsl = StoveOptionsDsl()
      stoveOptionsDsl.dumpReportOnTestFailure(true)

      stoveOptionsDsl.options.dumpReportOnTestFailure shouldBe true
    }

    test("should disable dump report on test failure") {
      val stoveOptionsDsl = StoveOptionsDsl()
      stoveOptionsDsl.dumpReportOnTestFailure(false)

      stoveOptionsDsl.options.dumpReportOnTestFailure shouldBe false
    }

    test("should configure reporting via DSL block") {
      val stoveOptionsDsl = StoveOptionsDsl()

      stoveOptionsDsl.reporting {
        enabled()
        dumpOnFailure()
      }

      stoveOptionsDsl.options.reportingEnabled shouldBe true
      stoveOptionsDsl.options.dumpReportOnTestFailure shouldBe true
    }

    test("should disable reporting via DSL block") {
      val stoveOptionsDsl = StoveOptionsDsl()
      stoveOptionsDsl.reportingEnabled(true)

      stoveOptionsDsl.reporting {
        disabled()
      }

      stoveOptionsDsl.options.reportingEnabled shouldBe false
    }

    test("should chain multiple options fluently") {
      val stoveOptionsDsl = StoveOptionsDsl()

      stoveOptionsDsl
        .reportingEnabled(true)
        .dumpReportOnTestFailure(true)
        .runMigrationsAlways()

      stoveOptionsDsl.options.reportingEnabled shouldBe true
      stoveOptionsDsl.options.dumpReportOnTestFailure shouldBe true
      stoveOptionsDsl.options.runMigrationsAlways shouldBe true
    }
  })
