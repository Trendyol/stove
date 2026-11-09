package com.trendyol.stove.quarkus

import com.trendyol.stove.system.BridgeSystem
import com.trendyol.stove.system.Stove
import com.trendyol.stove.system.WithDsl
import com.trendyol.stove.system.annotations.StoveDsl
import com.trendyol.stove.system.closeAll
import com.trendyol.stove.system.withBridgeSystem
import io.quarkus.arc.Arc
import io.quarkus.arc.ArcContainer
import io.quarkus.arc.InstanceHandle
import jakarta.enterprise.context.Dependent
import kotlinx.coroutines.runBlocking
import java.lang.reflect.Type
import java.util.concurrent.ConcurrentLinkedDeque
import kotlin.reflect.KClass
import kotlin.reflect.KType
import kotlin.reflect.jvm.javaType

/**
 * Resolves unqualified CDI beans from the running Quarkus application.
 *
 * Tests must load the augmented uber-jar instead of raw application classes, so ArC and
 * application types share the test classloader. Disable unused-bean removal in the e2e build.
 * Request scopes must already be active; dependent instances are destroyed when Stove stops.
 */
@StoveDsl
class QuarkusBridgeSystem(
  override val stove: Stove
) : BridgeSystem<Unit>(stove) {
  private var container: ArcContainer? = null
  private val dependentInstances = ConcurrentLinkedDeque<InstanceHandle<*>>()

  override suspend fun afterRun(context: Unit) {
    val applicationContainer = Arc.container()
    check(applicationContainer?.isRunning == true) {
      "Quarkus Bridge cannot access the running ArC container. $QUARKUS_BRIDGE_CLASSPATH_HELP"
    }
    container = applicationContainer
    super.afterRun(context)
  }

  override fun <D : Any> get(klass: KClass<D>): D = resolveBean(klass.java)

  override fun <D : Any> getByType(type: KType): D = resolveBean(type.javaType)

  private fun <D : Any> resolveBean(type: Type): D {
    val activeContainer = checkNotNull(container?.takeIf { it.isRunning }) {
      "Quarkus Bridge is not running. Call Stove.run() before resolving beans and do not use it after Stove.stop()."
    }
    val handle = activeContainer.instance<D>(type)
    val bean = checkNotNull(handle.get()) {
      "No unique CDI bean found for ${type.typeName}. Check for missing or ambiguous beans and set " +
        "quarkus.arc.remove-unused-beans=false in the e2e build."
    }
    if (handle.bean?.scope == Dependent::class.java) dependentInstances.addLast(handle)
    return bean
  }

  override fun close(): Unit = runBlocking {
    container = null
    closeAll(generateSequence { dependentInstances.pollLast() }.asIterable()) {
      it.destroy()
    }
  }
}

/** Registers CDI access for an augmented Quarkus application sharing the test classloader. */
fun WithDsl.bridge(): Stove = stove.withBridgeSystem(QuarkusBridgeSystem(stove))

internal const val QUARKUS_BRIDGE_CLASSPATH_HELP =
  "Build an uber-jar with quarkus.package.jar.type=uber-jar, put the augmented jar first on the test " +
    "runtime classpath, and exclude raw main output. Quarkus Bridge does not support the isolated fast-jar launcher."
