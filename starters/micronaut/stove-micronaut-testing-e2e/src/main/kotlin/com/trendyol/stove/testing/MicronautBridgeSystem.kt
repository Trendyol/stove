package com.trendyol.stove.testing

import com.trendyol.stove.testing.e2e.system.*
import com.trendyol.stove.testing.e2e.system.abstractions.*
import com.trendyol.stove.testing.e2e.system.annotations.StoveDsl
import io.micronaut.context.ApplicationContext
import kotlin.reflect.KClass

@StoveDsl
class MicronautBridgeSystem(
  override val testSystem: TestSystem
) : BridgeSystem<ApplicationContext>(testSystem),
  PluggedSystem,
  AfterRunAwareWithContext<ApplicationContext> {
  override fun <D : Any> get(klass: KClass<D>): D = ctx.getBean(klass.java)
}

@StoveDsl
fun WithDsl.bridge(): TestSystem = this.testSystem.withBridgeSystem(MicronautBridgeSystem(this.testSystem))
