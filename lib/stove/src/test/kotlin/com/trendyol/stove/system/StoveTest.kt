package com.trendyol.stove.system

import arrow.core.None
import arrow.core.Some
import com.trendyol.stove.reporting.StoveReporter
import com.trendyol.stove.system.abstractions.PluggedSystem
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

class StoveTest :
  FunSpec({

    context("Stove construction") {
      test("should create with default options") {
        val stove = Stove()

        stove.options.reportingEnabled shouldBe true
        stove.reporter.shouldBeInstanceOf<StoveReporter>()
      }

      test("should create with custom options via DSL") {
        val stove = Stove {
          keepDependenciesRunning()
        }

        stove.options.keepDependenciesRunning shouldBe true
      }

      test("should create with reporting enabled by default") {
        val stove = Stove()

        stove.options.reportingEnabled shouldBe true
        stove.reporter.isEnabled shouldBe true
      }
    }

    context("getOrRegister") {
      test("should register new system") {
        val stove = Stove()
        val system = TestPluggedSystem(stove)

        val result = stove.getOrRegister(system)

        result shouldBe system
        stove.activeSystems[TestPluggedSystem::class] shouldBe system
      }

      test("should return existing system if already registered") {
        val stove = Stove()
        val system1 = TestPluggedSystem(stove)
        val system2 = TestPluggedSystem(stove)

        stove.getOrRegister(system1)
        val result = stove.getOrRegister(system2)

        result shouldBe system1
      }
    }

    context("getOrNone") {
      test("should return None for unregistered system") {
        val stove = Stove()

        val result = stove.getOrNone<TestPluggedSystem>()

        result shouldBe None
      }

      test("should return Some for registered system") {
        val stove = Stove()
        val system = TestPluggedSystem(stove)
        stove.getOrRegister(system)

        val result = stove.getOrNone<TestPluggedSystem>()

        result shouldBe Some(system)
      }
    }

    context("with DSL") {
      test("should allow configuring via DSL") {
        val stove = Stove()

        val result = stove.with {
          // Configuration happens here
        }

        result shouldBe stove
      }
    }

    context("registerForDispose") {
      test("should register closeable for cleanup") {
        val stove = Stove()
        var closed = false
        val closeable = AutoCloseable { closed = true }

        stove.registerForDispose(closeable)
        stove.close()

        closed shouldBe true
      }

      test("should return the closeable") {
        val stove = Stove()
        val closeable = AutoCloseable { }

        val result = stove.registerForDispose(closeable)

        result shouldBe closeable
      }
    }

    context("close") {
      test("should handle errors gracefully during cleanup") {
        val stove = Stove()
        val failingCloseable = AutoCloseable { error("Cleanup failed") }
        stove.registerForDispose(failingCloseable)

        // Should not throw
        stove.close()
      }

      test("should cleanup multiple registered closeables") {
        val stove = Stove()
        var count = 0
        val closeable1 = AutoCloseable { count++ }
        val closeable2 = AutoCloseable { count++ }
        stove.registerForDispose(closeable1)
        stove.registerForDispose(closeable2)

        stove.close()

        count shouldBe 2
      }
    }

    context("activeSystems") {
      test("should be empty initially") {
        val stove = Stove()

        stove.activeSystems.isEmpty().shouldBeTrue()
      }

      test("should contain registered systems") {
        val stove = Stove()
        val system = TestPluggedSystem(stove)
        stove.getOrRegister(system)

        stove.activeSystems.size shouldBe 1
        stove.activeSystems.containsKey(TestPluggedSystem::class).shouldBeTrue()
      }
    }
  })

/**
 * Test implementation of PluggedSystem.
 */
class TestPluggedSystem(
  override val stove: Stove
) : PluggedSystem {
  override fun then(): Stove = stove

  override fun close() = Unit
}
