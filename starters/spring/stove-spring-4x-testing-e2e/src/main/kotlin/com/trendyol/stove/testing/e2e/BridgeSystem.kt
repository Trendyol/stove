package com.trendyol.stove.testing.e2e

import com.trendyol.stove.testing.e2e.system.*
import com.trendyol.stove.testing.e2e.system.abstractions.*
import com.trendyol.stove.testing.e2e.system.annotations.StoveDsl
import org.springframework.context.ApplicationContext
import kotlin.reflect.KClass

/**
 * A system that provides a bridge between the test system and the application context.
 *
 * @property testSystem the test system to bridge.
 */
@StoveDsl
class SpringBridgeSystem(
  override val testSystem: TestSystem
) : BridgeSystem<ApplicationContext>(testSystem),
  PluggedSystem,
  AfterRunAwareWithContext<ApplicationContext> {
  override fun <D : Any> get(klass: KClass<D>): D = ctx.getBean(klass.java)
}

/**
 * Returns the bridge system associated with the test system.
 *
 * @receiver the test system.
 * @return the bridge system.
 * @throws SystemNotRegisteredException if the bridge system is not registered.
 */
@StoveDsl
fun WithDsl.bridge(): TestSystem = this.testSystem.withBridgeSystem(SpringBridgeSystem(this.testSystem))
