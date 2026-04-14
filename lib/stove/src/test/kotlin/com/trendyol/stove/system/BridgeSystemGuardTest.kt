package com.trendyol.stove.system

import com.trendyol.stove.reporting.StoveTestContext
import com.trendyol.stove.system.abstractions.PluggedSystem
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.runBlocking
import kotlin.reflect.KClass

class BridgeSystemGuardTest :
  FunSpec({
    test("using throws when context is not initialized") {
      val stove = Stove()
      val bridge = UninitializedBridgeSystem(stove)
      stove.getOrRegister<BridgeSystem<TestCtx>>(bridge)
      stove.reporter.startTest(StoveTestContext("test-id", "test-name", "spec"))

      val error = shouldThrow<IllegalStateException> {
        runBlocking {
          ValidationDsl(stove).using<TestBean> { }
        }
      }

      error.message shouldContain "BridgeSystem context is not initialized"
      error.message shouldContain "providedApplication()"
    }

    test("using works after context is initialized") {
      val stove = Stove()
      val bean = TestBean("hello")
      val bridge = UninitializedBridgeSystem(stove, mapOf(TestBean::class to bean))
      stove.getOrRegister<BridgeSystem<TestCtx>>(bridge)

      // Initialize context
      runBlocking { bridge.afterRun(TestCtx()) }

      stove.reporter.startTest(StoveTestContext("test-id", "test-name", "spec"))

      runBlocking {
        ValidationDsl(stove).using<TestBean> {
          value shouldBe "hello"
        }
      }
    }
  })

private data class TestBean(val value: String)

private class TestCtx

private class UninitializedBridgeSystem(
  override val stove: Stove,
  private val beans: Map<KClass<*>, Any> = emptyMap()
) : BridgeSystem<TestCtx>(stove),
  PluggedSystem {
  override fun then(): Stove = stove

  @Suppress("UNCHECKED_CAST")
  override fun <D : Any> get(klass: KClass<D>): D =
    beans[klass] as? D ?: error("Missing bean for ${klass.simpleName}")
}
