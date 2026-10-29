package com.trendyol.stove.system

import arrow.core.None
import com.trendyol.stove.system.abstractions.*
import io.kotest.core.annotation.Isolate
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

// Changes the global Stove instance.
@Isolate
class StoveTest :
  FunSpec({
    test("getOrRegister returns existing instance") {
      val stove = Stove()
      val system = TestLifecycleSystem(stove)

      val first = stove.getOrRegister(system)
      val second = stove.getOrRegister(system)

      first shouldBe second
    }

    test("getOrNone returns None when system missing") {
      val stove = Stove()

      stove.getOrNone<TestLifecycleSystem>() shouldBe None
    }

    test("run invokes lifecycle and passes configurations") {
      val stove = Stove()
      val events = mutableListOf<String>()
      val system = TestLifecycleSystem(stove, events)
      val app = TestApplicationUnderTest(events)

      stove.getOrRegister(system)
      stove.applicationUnderTest(app)

      stove.run()

      events shouldBe listOf("beforeRun", "run", "configuration", "application.start", "afterRun")
      app.receivedConfigs shouldBe listOf("system.config=true")
      stove.applicationUnderTestContext<String>() shouldBe "context"
      Stove.instanceInitialized() shouldBe true
      stove { this.stove shouldBe stove }
      stove.close()
      events.takeLast(2) shouldBe listOf("system.close", "application.stop")
    }

    test("a failed resource close still releases later resources and stops the application") {
      val closed = mutableListOf<String>()
      val stove = Stove()
      stove.registerForDispose(
        AutoCloseable {
          closed.add("first resource")
          error("close failed")
        }
      )
      stove.registerForDispose(AutoCloseable { closed.add("second resource") })
      stove.applicationUnderTest(TestApplicationUnderTest { closed.add("application") })
      stove.run()

      stove.close()

      closed shouldBe listOf("first resource", "second resource", "application")
    }
  })

private class TestApplicationUnderTest(
  private val events: MutableList<String> = mutableListOf(),
  private val onStop: () -> Unit = {}
) : ApplicationUnderTest<String> {
  var receivedConfigs: List<String> = emptyList()

  override suspend fun start(configurations: List<String>): String {
    events.add("application.start")
    receivedConfigs = configurations
    return "context"
  }

  override suspend fun stop() {
    events.add("application.stop")
    onStop()
  }
}

private class TestLifecycleSystem(
  override val stove: Stove,
  private val events: MutableList<String> = mutableListOf()
) : PluggedSystem,
  BeforeRunAware,
  RunAware,
  AfterRunAware,
  ExposesConfiguration {
  override suspend fun beforeRun() {
    events.add("beforeRun")
  }

  override suspend fun run() {
    events.add("run")
  }

  override suspend fun stop() = Unit

  override suspend fun afterRun() {
    events.add("afterRun")
  }

  override fun configuration(): List<String> {
    events.add("configuration")
    return listOf("system.config=true")
  }

  override fun then(): Stove = stove

  override fun close() {
    events.add("system.close")
  }
}
