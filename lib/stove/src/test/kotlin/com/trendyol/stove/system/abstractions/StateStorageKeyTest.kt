package com.trendyol.stove.system.abstractions

import com.trendyol.stove.system.StoveOptions
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Path
import java.nio.file.Paths
import java.util.UUID
import kotlin.io.path.deleteIfExists
import kotlin.io.path.writeText

class StateStorageKeyTest : FunSpec({
  test("reopening the same keyed system recovers its configuration without starting it again") {
    StateStorageFixture().use { fixture ->
      val configuration = StoredConfiguration("primary-db")
      fixture.storage("primary").capture { configuration } shouldBe configuration

      fixture.storage("primary").capture { error("must reuse the existing system") } shouldBe configuration
    }
  }

  test("keyed systems keep independent configurations") {
    StateStorageFixture().use { fixture ->
      fixture.storage("primary").capture { StoredConfiguration("primary-db") }
      fixture.storage("analytics").capture { StoredConfiguration("analytics-db") }

      fixture.storage("primary").capture { error("must reuse primary") } shouldBe StoredConfiguration("primary-db")
      fixture.storage("analytics").capture { error("must reuse analytics") } shouldBe StoredConfiguration("analytics-db")
    }
  }

  test("disabling reuse discards saved state and starts a fresh system") {
    StateStorageFixture().use { fixture ->
      fixture.storage().capture { StoredConfiguration("old") }

      fixture.storage(reuse = false).capture { StoredConfiguration("fresh") } shouldBe StoredConfiguration("fresh")
      fixture.storage().capture { StoredConfiguration("next") } shouldBe StoredConfiguration("next")
    }
  }

  test("a failed start does not leave reusable state behind") {
    StateStorageFixture().use { fixture ->
      val storage = fixture.storage()
      shouldThrow<IllegalStateException> { storage.capture { error("startup failed") } }

      storage.capture { StoredConfiguration("recovered") } shouldBe StoredConfiguration("recovered")
      fixture.storage().capture { error("must reuse successful start") } shouldBe StoredConfiguration("recovered")
    }
  }

  test("saved state from another process is recognized and recovered") {
    StateStorageFixture().use { fixture ->
      val storage = fixture.storage()
      storage.capture { StoredConfiguration("current-process") }
      storage.isSubsequentRun() shouldBe false

      fixture.saveFromPreviousProcess()

      storage.isSubsequentRun() shouldBe true
      storage.capture { error("must recover previous process") } shouldBe StoredConfiguration("previous-process")
    }
  }

  test("unkeyed and null-key factories share the legacy snapshot location") {
    StateStorageFixture().use { fixture ->
      val configuration = StoredConfiguration("legacy")
      fixture.unkeyedStorage().capture { configuration }

      fixture.nullKeyStorage().capture { error("must recover the unkeyed snapshot") } shouldBe configuration
    }
  }
})

private class StateStorageFixture : AutoCloseable {
  private val factory = StateStorageFactory.Default()
  private val options = StoveOptions(keepDependenciesRunning = true)
  private val id = UUID.randomUUID().toString()
  private val snapshots = mutableSetOf<Path>()

  fun storage(key: String = "primary", reuse: Boolean = true): StateStorage<StoredConfiguration> {
    val name = "$id-$key"
    snapshots.add(snapshot(name))
    return factory.createWithKey(options.copy(keepDependenciesRunning = reuse), StorageFixtureSystem::class, StoredConfiguration::class, name)
  }

  fun unkeyedStorage(): StateStorage<StoredConfiguration> {
    snapshots.add(snapshot(null))
    return factory(options, StorageFixtureSystem::class, StoredConfiguration::class)
  }

  fun nullKeyStorage(): StateStorage<StoredConfiguration> =
    factory.createWithKey(options, StorageFixtureSystem::class, StoredConfiguration::class, null)

  fun saveFromPreviousProcess() {
    snapshot("$id-primary").writeText(
      """{"state":{"value":"previous-process"},"processId":-1}"""
    )
  }

  private fun snapshot(key: String?): Path = Paths.get(
    System.getProperty("java.io.tmpdir"),
    "com.trendyol.stove",
    "stove-e2e-storagefixturesystem${key?.let { "-$it" }.orEmpty()}.lock"
  )

  override fun close() {
    snapshots.forEach { it.deleteIfExists() }
  }
}

private class StorageFixtureSystem
private data class StoredConfiguration(val value: String) : ExposedConfiguration
