package com.trendyol.stove.system

import com.trendyol.stove.reporting.StoveTestContext
import com.trendyol.stove.system.abstractions.PluggedSystem
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.runBlocking
import kotlin.reflect.KClass

class BridgeSystemTest :
  FunSpec({
    test("using records success for single bean") {
      val stove = Stove()
      val serviceA = ServiceA("initial")
      val bridge = TestBridgeSystem(stove, mapOf(ServiceA::class to serviceA))
      stove.getOrRegister<BridgeSystem<TestContext>>(bridge)

      stove.reporter.startTest(StoveTestContext("test-id", "test-name", "spec"))

      runBlocking {
        ValidationDsl(stove).using<ServiceA> { value = "updated" }
      }

      val entry = stove.reporter
        .currentTest()
        .entries()
        .single()
      entry.isPassed shouldBe true
      entry.action shouldContain "Bean usage: ServiceA"
      serviceA.value shouldBe "updated"
    }

    test("using records failure and rethrows") {
      val stove = Stove()
      val serviceA = ServiceA("initial")
      val bridge = TestBridgeSystem(stove, mapOf(ServiceA::class to serviceA))
      stove.getOrRegister<BridgeSystem<TestContext>>(bridge)

      stove.reporter.startTest(StoveTestContext("test-id", "test-name", "spec"))

      val error = shouldThrow<IllegalStateException> {
        runBlocking {
          ValidationDsl(stove).using<ServiceA> { error("boom") }
        }
      }

      error.message shouldBe "boom"
      val entry = stove.reporter
        .currentTest()
        .entries()
        .single()
      entry.isFailed shouldBe true
      entry.action shouldContain "Bean usage: ServiceA"
    }

    test("using with two beans records success") {
      val stove = Stove()
      val serviceA = ServiceA("a")
      val serviceB = ServiceB(42)
      val bridge = TestBridgeSystem(
        stove,
        mapOf(
          ServiceA::class to serviceA,
          ServiceB::class to serviceB
        )
      )
      stove.getOrRegister<BridgeSystem<TestContext>>(bridge)

      stove.reporter.startTest(StoveTestContext("test-id", "test-name", "spec"))

      runBlocking {
        ValidationDsl(stove).using<ServiceA, ServiceB> { a, b ->
          a.value shouldBe "a"
          b.number shouldBe 42
        }
      }

      val entry = stove.reporter
        .currentTest()
        .entries()
        .single()
      entry.isPassed shouldBe true
      entry.action shouldContain "Bean usage: ServiceA, ServiceB"
    }
  })

private data class ServiceA(
  var value: String
)

private data class ServiceB(
  val number: Int
)

private class TestContext

private class TestBridgeSystem(
  override val stove: Stove,
  private val beans: Map<KClass<*>, Any>
) : BridgeSystem<TestContext>(stove),
  PluggedSystem {
  override fun then(): Stove = stove

  @Suppress("UNCHECKED_CAST")
  override fun <D : Any> get(klass: KClass<D>): D =
    beans[klass] as? D ?: error("Missing bean for ${klass.simpleName}")
}
