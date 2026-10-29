package com.trendyol.stove.system

import com.trendyol.stove.system.abstractions.*
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class StoveShutdownTest : FunSpec({
  test("restores faults before stopping the app and closes each resource once") {
    ShutdownFixture().use { fixture ->
      fixture.stove.run()
      fixture.stove.close()
      fixture.stove.close()

      fixture.events shouldBe listOf("app.start", "route.restore", "app.stop", "route.close", "dependency.close")
    }
  }

  test("restores resources in registration order and closes each group in reverse order") {
    ShutdownFixture().use { fixture ->
      fixture.stove.registerForDispose(AutoCloseable { fixture.events += "second.dependency.close" })
      fixture.stove.registerForDispose(ShutdownResource(fixture.stove, fixture.events, "second.route"))

      fixture.stove.run()
      fixture.stove.close()

      fixture.events shouldBe listOf(
        "app.start",
        "route.restore",
        "second.route.restore",
        "app.stop",
        "second.route.close",
        "route.close",
        "second.dependency.close",
        "dependency.close"
      )
    }
  }

  test("attempts every shutdown phase and preserves all cleanup failures") {
    ShutdownFixture().use { fixture ->
      val failure = IllegalStateException("restore")
      fixture.route.restoreFailure = failure
      fixture.application.stopFailure = IllegalStateException("stop")
      fixture.route.closeFailure = IllegalStateException("route")
      fixture.dependency.closeFailure = IllegalStateException("dependency")
      fixture.stove.run()

      shouldThrow<IllegalStateException> { fixture.stove.close() } shouldBe failure

      failure.suppressed.map { it.message } shouldBe listOf("stop", "route", "dependency")
      fixture.events shouldBe listOf("app.start", "route.restore", "app.stop", "route.close", "dependency.close")
    }
  }

  test("partially started application is stopped on startup failure") {
    ShutdownFixture().use { fixture ->
      val failure = IllegalArgumentException("start")
      fixture.application.startFailure = failure

      shouldThrow<IllegalArgumentException> { fixture.stove.run() } shouldBe failure

      fixture.events shouldBe listOf("app.start", "route.restore", "app.stop", "route.close", "dependency.close")
    }
  }

  test("afterRun failure rolls back the application and preserves cleanup errors") {
    ShutdownFixture().use { fixture ->
      fixture.dependency.afterRunFailure = IllegalStateException("afterRun")
      fixture.application.stopFailure = IllegalStateException("stop")

      val failure = shouldThrow<IllegalStateException> { fixture.stove.run() }

      failure.message shouldBe "afterRun"
      failure.suppressed.map { it.message } shouldBe listOf("stop")
      fixture.events shouldBe listOf("app.start", "route.restore", "app.stop", "route.close", "dependency.close")
    }
  }

  test("dependency startup failure cleans resources without stopping an unstarted app") {
    ShutdownFixture().use { fixture ->
      fixture.dependency.beforeRunFailure = IllegalStateException("dependency start")

      shouldThrow<IllegalStateException> { fixture.stove.run() }.message shouldBe "dependency start"

      fixture.events shouldBe listOf("route.restore", "route.close", "dependency.close")
    }
  }
})

private class ShutdownFixture : AutoCloseable {
  val events = mutableListOf<String>()
  val stove = Stove()
  val dependency = stove.getOrRegister(ShutdownDependency(stove, events))
  val route = stove.registerForDispose(ShutdownResource(stove, events))
  val application = ShutdownApp(events).also { stove.applicationUnderTest(it) }

  override fun close() = stove.close()
}

private class ShutdownDependency(
  override val stove: Stove,
  private val events: MutableList<String>
) : PluggedSystem, BeforeRunAware, AfterRunAware {
  var beforeRunFailure: Throwable? = null
  var afterRunFailure: Throwable? = null
  var closeFailure: Throwable? = null

  override suspend fun beforeRun() {
    beforeRunFailure?.let { throw it }
  }

  override suspend fun afterRun() {
    afterRunFailure?.let { throw it }
  }

  override fun close() {
    events += "dependency.close"
    closeFailure?.let { throw it }
  }
}

private class ShutdownResource(
  override val stove: Stove,
  private val events: MutableList<String>,
  private val name: String = "route"
) : ApplicationResource {
  var restoreFailure: Throwable? = null
  var closeFailure: Throwable? = null

  override suspend fun beforeApplicationStop() {
    events += "$name.restore"
    restoreFailure?.let { throw it }
  }

  override fun close() {
    events += "$name.close"
    closeFailure?.let { throw it }
  }
}

private class ShutdownApp(private val events: MutableList<String>) : ApplicationUnderTest<Unit> {
  var startFailure: Throwable? = null
  var stopFailure: Throwable? = null

  override suspend fun start(configurations: List<String>) {
    events += "app.start"
    startFailure?.let { throw it }
  }

  override suspend fun stop() {
    events += "app.stop"
    stopFailure?.let { throw it }
  }
}
