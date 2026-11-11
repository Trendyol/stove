package com.trendyol.stove.extensions.junit

import com.trendyol.stove.extensions.ReportProbe
import com.trendyol.stove.reporting.ReportEntry
import com.trendyol.stove.reporting.StoveTestFailureException
import com.trendyol.stove.system.Stove
import com.trendyol.stove.system.stove
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.*
import org.junit.jupiter.api.extension.AfterEachCallback
import org.junit.jupiter.api.extension.BeforeEachCallback
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.extension.ExtensionContext
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.junit.platform.engine.TestExecutionResult
import org.junit.platform.engine.discovery.DiscoverySelectors.selectClass
import org.junit.platform.testkit.engine.EngineTestKit

class JupiterLifecycleTest {
  @Test
  fun `templates, nested tests and failures balance reporting and clean up after execution`() {
    val result = EngineTestKit.engine("junit-jupiter")
      .selectors(selectClass(JupiterFixture::class.java))
      .execute()

    result.testEvents().assertStatistics { it.started(7).succeeded(6).failed(1) }
    JupiterFixture.reports.assertBalanced(7)
    JupiterFixture.cleanups shouldBe 7
    JupiterFixture.reports.entries.size shouldBe 7
    JupiterFixture.reports.failures.values.toList() shouldBe listOf("suspended assertion failed")
    val failure = result.testEvents().failed().list().single()
      .getRequiredPayload(TestExecutionResult::class.java).throwable.orElseThrow()
    failure.shouldBeInstanceOf<StoveTestFailureException>()
    failure.cause.shouldBeInstanceOf<AssertionError>()
    failure.message shouldContain "suspended assertion failed"
    ReportProbe.assertClean()
  }
}

@Tag("adapter-fixture")
@ExtendWith(JupiterCleanupProbe::class, StoveJUnitExtension::class)
class JupiterFixture {
  companion object {
    val reports = ReportProbe()
    var cleanups = 0

    @JvmStatic
    @BeforeAll
    fun start(): Unit = runBlocking {
      reports.reset()
      cleanups = 0
      ReportProbe.startStove()
      Stove.reporter().addListener(reports)
    }

    @JvmStatic
    @AfterAll
    fun stop() {
      try {
        ReportProbe.assertClean()
      } finally {
        Stove.stop()
      }
    }
  }

  // Identical display names must still represent separate template invocations.
  @RepeatedTest(value = 2, name = "same display name")
  suspend fun repeated() = exercise()

  @ParameterizedTest(name = "same display name")
  @ValueSource(ints = [1, 2])
  suspend fun parameterized(value: Int) {
    (value > 0) shouldBe true
    exercise()
  }

  @Test
  suspend fun failing() = exercise(fail = true)

  @Nested
  @DisplayName("orders")
  inner class Orders {
    @Test
    suspend fun first() = exercise(path = listOf("orders", "first()"))

    @Test
    suspend fun second() = exercise(path = listOf("orders", "second()"))
  }

  private suspend fun exercise(fail: Boolean = false, path: List<String>? = null) {
    val context = ReportProbe.assertActive(path)
    stove {
      delay(1)
      ReportProbe.assertActive(path) shouldBe context
      val reporter = Stove.reporter()
      reporter.record(
        if (fail) {
          ReportEntry.failure("Jupiter", context.testId, "assertion", "suspended assertion failed")
        } else {
          ReportEntry.success("Jupiter", context.testId, "assertion")
        }
      )
      if (fail) throw AssertionError("suspended assertion failed")
    }
  }
}

/** Registered outside Stove's extension so these callbacks check its actual boundaries. */
class JupiterCleanupProbe : BeforeEachCallback, AfterEachCallback {
  override fun beforeEach(context: ExtensionContext) = ReportProbe.assertClean()

  override fun afterEach(context: ExtensionContext) {
    ReportProbe.assertClean()
    JupiterFixture.cleanups++
  }
}
