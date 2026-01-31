package com.trendyol.stove.system

import com.trendyol.stove.system.abstractions.SystemNotRegisteredException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlin.reflect.KClass
import kotlin.reflect.typeOf

class BridgeSystemTest :
  FunSpec({

    context("BridgeSystem") {
      test("close should be no-op") {
        val stove = Stove()
        val bridge = TestBridgeSystem(stove)

        // Should not throw
        bridge.close()
      }

      test("afterRun should initialize context") {
        val stove = Stove()
        val bridge = TestBridgeSystem(stove)
        val context = TestContext("initialized")

        bridge.afterRun(context)

        bridge.getContext() shouldBe context
      }

      test("get should resolve dependency by KClass") {
        val stove = Stove()
        val bridge = TestBridgeSystem(stove)
        val service = TestService("test-service")
        bridge.registerDependency(TestService::class, service)

        val resolved = bridge.get(TestService::class)

        resolved shouldBe service
      }

      test("getByType should resolve dependency by KType") {
        val stove = Stove()
        val bridge = TestBridgeSystem(stove)
        val service = TestService("test-service")
        bridge.registerDependency(TestService::class, service)

        val resolved: TestService = bridge.getByType(typeOf<TestService>())

        resolved shouldBe service
      }

      test("get should throw for unregistered dependency") {
        val stove = Stove()
        val bridge = TestBridgeSystem(stove)

        shouldThrow<IllegalArgumentException> {
          bridge.get(TestService::class)
        }.message shouldBe "Dependency TestService not registered"
      }

      test("reportSystemName should return TestBridge") {
        val stove = Stove()
        val bridge = TestBridgeSystem(stove)

        bridge.reportSystemName shouldBe "TestBridge"
      }
    }

    context("Stove.withBridgeSystem") {
      test("should register bridge system") {
        val stove = Stove()
        val bridge = TestBridgeSystem(stove)

        val result = stove.withBridgeSystem(bridge)

        result shouldBe stove
        // BridgeSystem is registered with BridgeSystem::class as the key, not TestBridgeSystem
        stove.getOrNone<BridgeSystem<*>>().isSome() shouldBe true
      }
    }

    context("Stove.bridge") {
      test("should return registered bridge") {
        val stove = Stove()
        val bridge = TestBridgeSystem(stove)
        stove.withBridgeSystem(bridge)

        val result = stove.bridge()

        result shouldBe bridge
      }

      test("should throw when bridge not registered") {
        val stove = Stove()

        shouldThrow<SystemNotRegisteredException> {
          stove.bridge()
        }
      }
    }

    context("WithDsl.bridge") {
      test("should register bridge via DSL") {
        val stove = Stove()
        val bridge = TestBridgeSystem(stove)
        val withDsl = WithDsl(stove)

        withDsl.bridge(bridge)

        // BridgeSystem is registered with BridgeSystem::class as the key, not TestBridgeSystem
        stove.getOrNone<BridgeSystem<*>>().isSome() shouldBe true
      }
    }
  })

/**
 * Test context for BridgeSystem.
 */
data class TestContext(
  val value: String
)

/**
 * Test service for dependency resolution.
 */
data class TestService(
  val name: String
)

/**
 * Test implementation of BridgeSystem.
 */
class TestBridgeSystem(
  stove: Stove
) : BridgeSystem<TestContext>(stove) {
  private val dependencies = mutableMapOf<KClass<*>, Any>()

  fun <D : Any> registerDependency(klass: KClass<D>, instance: D) {
    dependencies[klass] = instance
  }

  fun getContext(): TestContext = ctx

  @Suppress("UNCHECKED_CAST")
  override fun <D : Any> get(klass: KClass<D>): D =
    dependencies[klass] as? D
      ?: throw IllegalArgumentException("Dependency ${klass.simpleName} not registered")
}
