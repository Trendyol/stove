package com.trendyol.stove.testing.e2e

import com.trendyol.stove.testing.e2e.system.*
import com.trendyol.stove.testing.e2e.system.abstractions.*
import com.trendyol.stove.testing.e2e.system.annotations.StoveDsl
import io.ktor.server.application.*
import org.koin.ktor.ext.*
import kotlin.reflect.KClass

/**
 * A system that provides a bridge between the test system and the application context.
 *
 * @property testSystem the test system to bridge.
 */
@StoveDsl
class KtorBridgeSystem(
  override val testSystem: TestSystem
) : BridgeSystem<Application>(testSystem),
  PluggedSystem,
  AfterRunAwareWithContext<Application> {
  override fun <D : Any> get(klass: KClass<D>): D = ctx.getKoin().get(klass)
}

@StoveDsl
fun WithDsl.bridge(): TestSystem = this.testSystem.withBridgeSystem(KtorBridgeSystem(this.testSystem))
