package com.trendyol.stove.system

import com.trendyol.stove.reporting.StoveTestContext
import com.trendyol.stove.system.abstractions.PluggedSystem
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlin.reflect.KClass

class BridgeSystemTest :
  FunSpec({
    test("using resolves a bean and reports the successful operation") {
      val service = ServiceA("initial")
      val fixture = BridgeFixture(service).start()

      fixture.dsl.using<ServiceA> { value = "updated" }

      service.value shouldBe "updated"
      fixture.entry.isPassed shouldBe true
      fixture.entry.action shouldBe "Bean usage: ServiceA"
      fixture.entry.metadata shouldBe mapOf("type" to ServiceA::class.qualifiedName)
    }

    test("using reports a failed operation and rethrows its error") {
      val fixture = BridgeFixture(ServiceA("initial")).start()

      val failure = shouldThrow<IllegalStateException> {
        fixture.dsl.using<ServiceA> { error("boom") }
      }

      failure.message shouldBe "boom"
      fixture.entry.isFailed shouldBe true
      fixture.entry.action shouldBe "Bean usage: ServiceA"
    }

    test("using resolves multiple beans in declaration order") {
      val fixture = BridgeFixture(ServiceA("a"), ServiceB(42)).start()

      fixture.dsl.using<ServiceA, ServiceB> { a, b ->
        a.value = "value-${b.number}"
      }

      fixture.dsl.using<ServiceA> { value shouldBe "value-42" }
      val entry = fixture.entries.first()
      entry.isPassed shouldBe true
      entry.action shouldBe "Bean usage: ServiceA, ServiceB"
      entry.metadata shouldBe mapOf("types" to listOf(ServiceA::class.qualifiedName, ServiceB::class.qualifiedName))
      fixture.entries.size shouldBe 2
    }

    test("a missing bean is reported before the validation can run") {
      val fixture = BridgeFixture(ServiceA("a")).start()
      var validationRan = false

      val failure = shouldThrow<IllegalStateException> {
        fixture.dsl.using<ServiceA, ServiceB> { _, _ -> validationRan = true }
      }

      failure.message shouldContain "Missing bean for ServiceB"
      validationRan shouldBe false
      fixture.entry.isFailed shouldBe true
      fixture.entry.action shouldBe "Bean usage: ServiceA, ServiceB"
    }
  })

private class BridgeFixture(vararg beans: Any) {
  private val stove = Stove()
  private val bridge = TestBridgeSystem(stove, beans.associateBy { it::class })
  val dsl = ValidationDsl(stove)
  val entries get() = stove.reporter.currentTest().entries()
  val entry get() = entries.single()

  suspend fun start(): BridgeFixture {
    stove.withBridgeSystem(bridge)
    bridge.afterRun(TestContext())
    stove.reporter.startTest(StoveTestContext("test-id", "test-name", "spec"))
    return this
  }
}

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
