package com.trendyol.stove.spring

import com.trendyol.stove.system.*
import com.trendyol.stove.system.abstractions.*
import com.trendyol.stove.system.annotations.StoveDsl
import org.springframework.context.ApplicationContext
import kotlin.reflect.KClass

/**
 * A system that provides a bridge between the test system and the application context.
 *
 * @property stove the test system to bridge.
 */
@StoveDsl
class SpringBridgeSystem(
  override val stove: Stove
) : BridgeSystem<ApplicationContext>(stove),
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
fun WithDsl.bridge(): Stove = this.stove.withBridgeSystem(SpringBridgeSystem(this.stove))
