package com.trendyol.stove.testing.e2e.system

import com.trendyol.stove.testing.e2e.system.abstractions.*
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlin.reflect.KClass

class TestSystemOptionsDslTest : FunSpec({
  test("should keep dependencies running") {
    val testSystemOptionsDsl = TestSystemOptionsDsl()
    testSystemOptionsDsl.keepDependenciesRunning()

    testSystemOptionsDsl.options.keepDependenciesRunning shouldBe true
  }

  test("should check if running locally") {
    val testSystemOptionsDsl = TestSystemOptionsDsl()
    testSystemOptionsDsl.isRunningLocally() shouldBe true
  }

  test("should enable reuse for test containers") {
    val testSystemOptionsDsl = TestSystemOptionsDsl()
    testSystemOptionsDsl.enableReuseForTestContainers()
  }

  test("should set state storage factory") {
    val testSystemOptionsDsl = TestSystemOptionsDsl()

    class AnotherStateStorageFactory : StateStorageFactory {
      override fun <T : Any> invoke(options: TestSystemOptions, system: KClass<*>, state: KClass<T>): StateStorage<T> {
        return object : StateStorage<T> {
          override suspend fun capture(start: suspend () -> T): T {
            return start()
          }

          override fun isSubsequentRun(): Boolean {
            return false
          }
        }
      }
    }

    val anotherStateStorageFactory = AnotherStateStorageFactory()
    testSystemOptionsDsl.stateStorage(anotherStateStorageFactory)

    testSystemOptionsDsl.options.stateStorageFactory shouldBe anotherStateStorageFactory
  }

  test("should run migrations always") {
    val testSystemOptionsDsl = TestSystemOptionsDsl()
    testSystemOptionsDsl.runMigrationsAlways()

    testSystemOptionsDsl.options.runMigrationsAlways shouldBe true
  }
})
