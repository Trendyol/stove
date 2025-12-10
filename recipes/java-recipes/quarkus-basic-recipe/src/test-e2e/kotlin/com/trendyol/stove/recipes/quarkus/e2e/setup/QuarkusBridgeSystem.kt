package com.trendyol.stove.recipes.quarkus.e2e.setup

import com.trendyol.stove.testing.e2e.system.*
import com.trendyol.stove.testing.e2e.system.abstractions.*
import com.trendyol.stove.testing.e2e.system.annotations.StoveDsl
import java.lang.reflect.*
import kotlin.reflect.KClass
import kotlin.reflect.KType

/**
 * Bridge system for accessing Quarkus Arc container beans.
 *
 * Creates JDK dynamic proxies to bridge classloader boundaries,
 * enabling the standard Stove `using<T>` pattern.
 *
 * **Requirements:**
 * - Beans must be registered by interface (CDI best practice)
 * - Interface must exist in both test and application classpaths
 *
 * **Limitations:**
 * - Only interfaces can be proxied (JDK proxy limitation)
 * - Complex objects passed TO Quarkus fail (classloader mismatch)
 * - Complex objects returned FROM Quarkus fail (ClassCastException)
 * - Use primitives/Strings for cross-classloader communication
 *
 * **Example:**
 * ```kotlin
 * validate {
 *   using<HelloService> { hello() }
 *   using<List<PaymentService>> { forEach { it.process() } }
 * }
 * ```
 */
@StoveDsl
class QuarkusBridgeSystem(
  override val testSystem: TestSystem
) : BridgeSystem<Unit>(testSystem) {

  @Suppress("UNCHECKED_CAST")
  override fun <D : Any> get(klass: KClass<D>): D {
    val realBean = ArcContainerAccessor.resolveBean(klass.java.name)
    return createProxy(klass.java, realBean) as D
  }

  @Suppress("UNCHECKED_CAST")
  override fun <D : Any> getByType(type: KType): D {
    val klass = type.classifier as? KClass<*>
      ?: throw IllegalArgumentException("Cannot resolve type: $type")

    // Handle Iterable types (List, Set, Collection)
    if (isIterableType(klass)) {
      return resolveIterableType(type, klass) as D
    }

    return get(klass as KClass<D>)
  }

  // ---- Private helpers ----

  private fun isIterableType(klass: KClass<*>): Boolean =
    Iterable::class.java.isAssignableFrom(klass.java)

  private fun resolveIterableType(type: KType, klass: KClass<*>): Any {
    val elementClass = extractElementClass(type)
    val allBeans = ArcContainerAccessor.resolveAllBeans(elementClass.java.name)
    val proxiedBeans = allBeans.map { createProxy(elementClass.java, it) }

    return when {
      Set::class.java.isAssignableFrom(klass.java) -> proxiedBeans.toSet()
      else -> proxiedBeans
    }
  }

  private fun extractElementClass(type: KType): KClass<*> {
    val elementType = type.arguments.firstOrNull()?.type
      ?: throw IllegalArgumentException("Iterable must have element type: $type")
    return elementType.classifier as? KClass<*>
      ?: throw IllegalArgumentException("Cannot resolve element type: $elementType")
  }

  private fun createProxy(javaClass: Class<*>, realBean: Any): Any {
    val interfaces = resolveInterfaces(javaClass)
    return Proxy.newProxyInstance(
      javaClass.classLoader,
      interfaces,
      BeanInvocationHandler(realBean)
    )
  }

  private fun resolveInterfaces(javaClass: Class<*>): Array<Class<*>> = when {
    javaClass.isInterface -> arrayOf(javaClass)
    javaClass.interfaces.isNotEmpty() -> javaClass.interfaces
    else -> error(
      "Class ${javaClass.name} has no interfaces. " +
        "Use interfaces for Quarkus beans to enable type-safe bridge."
    )
  }
}

/**
 * Invocation handler that delegates to Quarkus beans via reflection.
 */
private class BeanInvocationHandler(
  private val realBean: Any
) : InvocationHandler {

  override fun invoke(proxy: Any?, method: Method, args: Array<out Any>?): Any? =
    QuarkusContext.withContext {
      val targetMethod = realBean.javaClass.getMethod(method.name, *method.parameterTypes)
      if (args != null) {
        targetMethod.invoke(realBean, *args)
      } else {
        targetMethod.invoke(realBean)
      }
    }
}

/**
 * DSL function to enable the Quarkus bridge system.
 */
@StoveDsl
fun WithDsl.bridge(): TestSystem =
  this.testSystem.withBridgeSystem(QuarkusBridgeSystem(this.testSystem))
