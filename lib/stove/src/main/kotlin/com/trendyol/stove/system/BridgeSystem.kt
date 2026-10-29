package com.trendyol.stove.system

import arrow.core.getOrElse
import com.trendyol.stove.reporting.*
import com.trendyol.stove.system.abstractions.*
import com.trendyol.stove.system.annotations.StoveDsl
import kotlin.reflect.*

/**
 * A system that provides a bridge between the test system and the application context.
 *
 * @property stove the test system to bridge.
 */
@StoveDsl
abstract class BridgeSystem<T : Any>(
  override val stove: Stove
) : PluggedSystem,
  AfterRunAwareWithContext<T>,
  Reports {
  /**
   * The application context used to resolve dependencies.
   */
  protected lateinit var ctx: T

  /**
   * Closes the bridge system.
   */
  override fun close(): Unit = Unit

  /**
   * Initializes the bridge system after the test run.
   *
   * @param context the application context.
   */
  override suspend fun afterRun(context: T) {
    ctx = context
  }

  /**
   * Resolves a dependency by KClass.
   * Override this for basic type resolution without generic support.
   */
  abstract fun <D : Any> get(klass: KClass<D>): D

  /**
   * Resolves a dependency by KType, preserving generic type information.
   * Override this to support generic types like List<T>, Map<K,V>, etc.
   * Default implementation falls back to KClass-based resolution.
   *
   * @param type the full KType including generic parameters
   * @return the resolved dependency
   */
  @Suppress("UNCHECKED_CAST")
  open fun <D : Any> getByType(type: KType): D {
    val klass = type.classifier as? KClass<D>
      ?: throw IllegalArgumentException("Cannot resolve type: $type")
    return get(klass)
  }

  /**
   * Checks that the application context has been initialized.
   * Throws with a clear error message if it hasn't (e.g., when using providedApplication()).
   */
  @PublishedApi
  internal fun ensureContextInitialized() {
    check(::ctx.isInitialized) {
      "BridgeSystem context is not initialized. " +
        "Ensure a JVM starter (springBoot/ktor/micronaut) is configured and Stove.run() has been called. " +
        "Note: providedApplication() does not support Bridge because the remote application's DI container is not accessible."
    }
  }

  /**
   * Resolves a bean of the specified type from the application context.
   * Uses KType to preserve generic type information (e.g., List<PaymentService>).
   *
   * @param T the type of bean to resolve.
   * @return the resolved bean.
   */
  @PublishedApi
  internal inline fun <reified D : Any> resolve(): D = getByType(typeOf<D>())

  /**
   * Executes the specified block using the resolved bean.
   * If you need to capture values, declare variables outside the block and assign inside.
   *
   * @param D the type of bean to resolve.
   * @param block the block to execute with the resolved bean as receiver.
   */
  suspend inline fun <reified D : Any> using(block: suspend D.() -> Unit) {
    ensureContextInitialized()
    reportBeanUsage(listOf(D::class)) { block(resolve()) }
  }
}

@PublishedApi
@Suppress("TooGenericExceptionCaught")
internal suspend inline fun BridgeSystem<*>.reportBeanUsage(types: List<KClass<*>>, block: () -> Unit) {
  val beanNames = types.joinToString(", ") { it.simpleName ?: "Unknown" }
  val metadata = if (types.size == 1) {
    mapOf("type" to (types.single().qualifiedName ?: ""))
  } else {
    mapOf("types" to types.map { it.qualifiedName })
  }
  try {
    block()
    reporter.record(
      ReportEntry.success(
        system = reportSystemName,
        testId = reporter.currentTestId(),
        action = "Bean usage: $beanNames",
        metadata = metadata
      )
    )
  } catch (failure: Throwable) {
    reporter.record(
      ReportEntry.failure(
        system = reportSystemName,
        testId = reporter.currentTestId(),
        action = "Bean usage: $beanNames",
        error = failure.message ?: "Unknown error",
        metadata = metadata
      )
    )
    throw failure
  }
}

/**
 * Adds a bridge system to Stove and returns the modified Stove instance.
 *
 * @receiver Stove instance to modify.
 * @return the modified Stove instance.
 */
fun <T : Any> Stove.withBridgeSystem(bridge: BridgeSystem<T>): Stove = getOrRegister(bridge).let { this }

/**
 * Returns the bridge system associated with Stove.
 * This function is only available in the validation DSL.
 *
 * @receiver Stove instance.
 * @return the bridge system.
 * @throws SystemNotRegisteredException if the bridge system is not registered.
 */
@PublishedApi
internal fun Stove.bridge(): BridgeSystem<*> = getOrNone<BridgeSystem<*>>().getOrElse {
  throw SystemNotRegisteredException(BridgeSystem::class)
}

/**
 * Returns the bridge system associated with Stove.
 *
 * @receiver Stove instance.
 * @return the bridge system.
 * @throws SystemNotRegisteredException if the bridge system is not registered.
 */
fun <T : Any> WithDsl.bridge(of: BridgeSystem<T>): Stove = this.stove.withBridgeSystem(of)

/**
 * Executes the specified block using the resolved bean from the bridge system.
 * Resolved beans are using physical components of the application.
 *
 * Suggested usage: validating or preparing the application state without accessing the physical components directly.
 * If you need to capture values from inside the block, declare variables outside and assign inside:
 *
 * ```kotlin
 *  stove {
 *      // Simple assertion
 *      using<PersonService> {
 *          serviceName shouldBe "personService"
 *          find(userId = 123) shouldBe Person(id = 123, name = "John Doe")
 *      }
 *
 *      // Capturing a value for later use
 *      var userId: Long = 0
 *      using<UserRepository> {
 *          userId = save(User(name = "John")).id
 *      }
 *      // Use userId in subsequent operations
 *  }
 * ```
 *
 * @receiver the validation DSL.
 * @param T the type of bean to resolve.
 * @param block the block to execute with the resolved bean as receiver.
 */
suspend inline fun <reified T : Any> ValidationDsl.using(
  block: @StoveDsl suspend T.() -> Unit
): Unit = this.stove.bridge().using(block)

/**
 * Executes the specified block using two resolved beans.
 *
 * @param T1 the type of the first bean to resolve.
 * @param T2 the type of the second bean to resolve.
 * @param validation the block to execute with the resolved beans.
 */
suspend inline fun <
  reified T1 : Any,
  reified T2 : Any
  > ValidationDsl.using(
  crossinline validation: suspend (T1, T2) -> Unit
): Unit = stove.bridge().let { bridge ->
  bridge.ensureContextInitialized()
  bridge.reportBeanUsage(listOf(T1::class, T2::class)) {
    validation(bridge.resolve<T1>(), bridge.resolve<T2>())
  }
}

/**
 * Executes the specified block using three resolved beans.
 *
 * @param T1 the type of the first bean to resolve.
 * @param T2 the type of the second bean to resolve.
 * @param T3 the type of the third bean to resolve.
 * @param validation the block to execute with the resolved beans.
 */
suspend inline fun <
  reified T1 : Any,
  reified T2 : Any,
  reified T3 : Any
  > ValidationDsl.using(
  crossinline validation: suspend (T1, T2, T3) -> Unit
): Unit = stove.bridge().let { bridge ->
  bridge.ensureContextInitialized()
  bridge.reportBeanUsage(listOf(T1::class, T2::class, T3::class)) {
    validation(bridge.resolve<T1>(), bridge.resolve<T2>(), bridge.resolve<T3>())
  }
}

/**
 * Executes the specified block using four resolved beans.
 *
 * @param T1 the type of the first bean to resolve.
 * @param T2 the type of the second bean to resolve.
 * @param T3 the type of the third bean to resolve.
 * @param T4 the type of the fourth bean to resolve.
 * @param validation the block to execute with the resolved beans.
 */
suspend inline fun <
  reified T1 : Any,
  reified T2 : Any,
  reified T3 : Any,
  reified T4 : Any
  > ValidationDsl.using(
  crossinline validation: suspend (T1, T2, T3, T4) -> Unit
): Unit = stove.bridge().let { bridge ->
  bridge.ensureContextInitialized()
  bridge.reportBeanUsage(listOf(T1::class, T2::class, T3::class, T4::class)) {
    validation(bridge.resolve<T1>(), bridge.resolve<T2>(), bridge.resolve<T3>(), bridge.resolve<T4>())
  }
}

/**
 * Executes the specified block using five resolved beans.
 *
 * @param T1 the type of the first bean to resolve.
 * @param T2 the type of the second bean to resolve.
 * @param T3 the type of the third bean to resolve.
 * @param T4 the type of the fourth bean to resolve.
 * @param T5 the type of the fifth bean to resolve.
 * @param validation the block to execute with the resolved beans.
 */
suspend inline fun <
  reified T1 : Any,
  reified T2 : Any,
  reified T3 : Any,
  reified T4 : Any,
  reified T5 : Any
  > ValidationDsl.using(
  crossinline validation: suspend (T1, T2, T3, T4, T5) -> Unit
): Unit = stove.bridge().let { bridge ->
  bridge.ensureContextInitialized()
  bridge.reportBeanUsage(listOf(T1::class, T2::class, T3::class, T4::class, T5::class)) {
    validation(bridge.resolve<T1>(), bridge.resolve<T2>(), bridge.resolve<T3>(), bridge.resolve<T4>(), bridge.resolve<T5>())
  }
}
