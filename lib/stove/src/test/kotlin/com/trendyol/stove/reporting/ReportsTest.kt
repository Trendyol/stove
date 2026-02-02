package com.trendyol.stove.reporting

import com.trendyol.stove.system.Stove
import com.trendyol.stove.system.abstractions.PluggedSystem
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

class ReportsTest :
  FunSpec({

    context("reportSystemName") {
      test("should return class name without System suffix") {
        val stove = Stove()
        val reports = TestReportsSystem(stove)

        reports.reportSystemName shouldBe "TestReports"
      }

      test("should handle class name ending with System") {
        val stove = Stove()
        val reports = AnotherTestSystem(stove)

        reports.reportSystemName shouldBe "AnotherTest"
      }
    }

    context("reporter") {
      test("should return reporter from PluggedSystem") {
        val stove = Stove()
        val reports = TestReportsSystem(stove)

        reports.reporter shouldBe stove.reporter
      }

      test("should throw when not a PluggedSystem") {
        val reports = object : Reports {}

        shouldThrow<IllegalStateException> {
          reports.reporter
        }.message shouldContain "Reports must be implemented by a PluggedSystem"
      }
    }

    context("snapshot") {
      test("should return default snapshot") {
        val stove = Stove()
        val reports = TestReportsSystem(stove)

        val snapshot = reports.snapshot()

        snapshot.system shouldBe "TestReports"
        snapshot.state shouldBe emptyMap()
        snapshot.summary shouldBe "No detailed state available"
      }

      test("can be overridden to provide custom snapshot") {
        val stove = Stove()
        val reports = CustomSnapshotSystem(stove)

        val snapshot = reports.snapshot()

        snapshot.system shouldBe "CustomSnapshot"
        snapshot.state shouldBe mapOf("key" to "value")
        snapshot.summary shouldBe "Custom snapshot"
      }
    }
  })

/**
 * Test implementation of Reports interface that also implements PluggedSystem.
 */
private class TestReportsSystem(
  override val stove: Stove
) : PluggedSystem,
  Reports {
  override fun then(): Stove = stove

  override fun close() = Unit
}

/**
 * Another test system to verify suffix removal.
 */
private class AnotherTestSystem(
  override val stove: Stove
) : PluggedSystem,
  Reports {
  override fun then(): Stove = stove

  override fun close() = Unit
}

/**
 * Test system with custom snapshot.
 */
private class CustomSnapshotSystem(
  override val stove: Stove
) : PluggedSystem,
  Reports {
  override fun then(): Stove = stove

  override fun close() = Unit

  override fun snapshot(): SystemSnapshot = SystemSnapshot(
    system = reportSystemName,
    state = mapOf("key" to "value"),
    summary = "Custom snapshot"
  )
}
