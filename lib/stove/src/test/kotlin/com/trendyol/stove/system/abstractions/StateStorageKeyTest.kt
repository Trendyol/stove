package com.trendyol.stove.system.abstractions

import com.trendyol.stove.system.StoveOptions
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.nio.file.Paths

class StateStorageKeyTest :
  FunSpec({
    test("FileSystemStorage without key uses original path format") {
      val options = StoveOptions()
      val storage = FileSystemStorage<TestConfig>(options, TestSystem::class, TestConfig::class)
      val expectedPath = Paths.get(
        System.getProperty("java.io.tmpdir"),
        "com.trendyol.stove",
        "stove-e2e-testsystem.lock"
      )

      // Access via reflection to verify path — the class is internal
      val pathField = storage.javaClass.getDeclaredField("pathForSystem")
      pathField.isAccessible = true
      val path = pathField.get(storage) as java.nio.file.Path

      path shouldBe expectedPath
    }

    test("FileSystemStorage with key includes key in path") {
      val options = StoveOptions()
      val storage = FileSystemStorage<TestConfig>(options, TestSystem::class, TestConfig::class, keyName = "AppDb")
      val expectedPath = Paths.get(
        System.getProperty("java.io.tmpdir"),
        "com.trendyol.stove",
        "stove-e2e-testsystem-appdb.lock"
      )

      val pathField = storage.javaClass.getDeclaredField("pathForSystem")
      pathField.isAccessible = true
      val path = pathField.get(storage) as java.nio.file.Path

      path shouldBe expectedPath
    }

    test("different keys produce different lock file paths") {
      val options = StoveOptions()
      val storageA = FileSystemStorage<TestConfig>(options, TestSystem::class, TestConfig::class, keyName = "AppDb")
      val storageB =
        FileSystemStorage<TestConfig>(options, TestSystem::class, TestConfig::class, keyName = "AnalyticsDb")

      val pathField = FileSystemStorage::class.java.getDeclaredField("pathForSystem")
      pathField.isAccessible = true

      val pathA = pathField.get(storageA) as java.nio.file.Path
      val pathB = pathField.get(storageB) as java.nio.file.Path

      pathA shouldNotBe pathB
      pathA.toString() shouldContain "appdb"
      pathB.toString() shouldContain "analyticsdb"
    }

    test("StateStorageFactory createWithKey default delegates to invoke") {
      var invokedWithSystem: Class<*>? = null
      val factory = object : StateStorageFactory {
        override fun <T : Any> invoke(
          options: StoveOptions,
          system: kotlin.reflect.KClass<*>,
          state: kotlin.reflect.KClass<T>
        ): StateStorage<T> {
          invokedWithSystem = system.java
          return FileSystemStorage(options, system, state)
        }
      }

      factory.createWithKey(StoveOptions(), TestSystem::class, TestConfig::class, "SomeKey")

      invokedWithSystem shouldBe TestSystem::class.java
    }

    test("DefaultStateStorageFactory createWithKey passes keyName to FileSystemStorage") {
      val factory = StateStorageFactory.Default()
      val storage = factory.createWithKey(StoveOptions(), TestSystem::class, TestConfig::class, "MyKey")

      val pathField = storage.javaClass.getDeclaredField("pathForSystem")
      pathField.isAccessible = true
      val path = pathField.get(storage) as java.nio.file.Path

      path.toString() shouldContain "mykey"
    }

    test("DefaultStateStorageFactory createWithKey with null key behaves like no key") {
      val factory = StateStorageFactory.Default()
      val storage = factory.createWithKey(StoveOptions(), TestSystem::class, TestConfig::class, null)

      val pathField = storage.javaClass.getDeclaredField("pathForSystem")
      pathField.isAccessible = true
      val path = pathField.get(storage) as java.nio.file.Path

      path.fileName.toString() shouldBe "stove-e2e-testsystem.lock"
      path.fileName.toString() shouldNotContain "-null"
    }
  })

private class TestSystem

private data class TestConfig(val value: String = "test") : ExposedConfiguration
