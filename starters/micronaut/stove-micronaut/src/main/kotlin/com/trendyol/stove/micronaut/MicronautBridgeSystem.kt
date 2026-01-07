package com.trendyol.stove.micronaut

import com.trendyol.stove.system.*
import com.trendyol.stove.system.abstractions.*
import com.trendyol.stove.system.annotations.StoveDsl
import io.micronaut.context.ApplicationContext
import kotlin.reflect.KClass

@StoveDsl
class MicronautBridgeSystem(
  override val stove: Stove
) : BridgeSystem<ApplicationContext>(stove),
  PluggedSystem,
  AfterRunAwareWithContext<ApplicationContext> {
  override fun <D : Any> get(klass: KClass<D>): D = ctx.getBean(klass.java)
}

@StoveDsl
fun WithDsl.bridge(): Stove = this.stove.withBridgeSystem(MicronautBridgeSystem(this.stove))
