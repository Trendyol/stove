package com.trendyol.stove.testing.e2e.system

import arrow.core.getOrElse
import com.trendyol.stove.testing.e2e.system.abstractions.*
import com.trendyol.stove.testing.e2e.system.annotations.StoveDsl
import kotlin.reflect.KClass

/**
 * A system that provides a bridge between the test system and the application context.
 *
 * @property testSystem the test system to bridge.
 */
@StoveDsl
abstract class BridgeSystem<T : Any>(
  override val testSystem: TestSystem
) : PluggedSystem,
  AfterRunAwareWithContext<T> {
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

  abstract fun <D : Any> get(klass: KClass<D>): D

  /**
   * Resolves a bean of the specified type from the application context.
   *
   * @param T the type of bean to resolve.
   * @return the resolved bean.
   */
  @PublishedApi
  internal inline fun <reified D : Any> resolve(): D = get(D::class)

  /**
   * Executes the specified block using the resolved bean.
   * If you need to capture values, declare variables outside the block and assign inside.
   *
   * @param D the type of bean to resolve.
   * @param block the block to execute with the resolved bean as receiver.
   */
  @StoveDsl
  inline fun <reified D : Any> using(block: D.() -> Unit): Unit = block(resolve())
}

/**
 * Adds a bridge system to the test system and returns the modified test system.
 *
 * @receiver the test system to modify.
 * @return the modified test system.
 */
fun <T : Any> TestSystem.withBridgeSystem(bridge: BridgeSystem<T>): TestSystem = getOrRegister(bridge).let { this }

/**
 * Returns the bridge system associated with the test system.
 * This function is only available in the validation DSL.
 *
 * @receiver the test system.
 * @return the bridge system.
 * @throws SystemNotRegisteredException if the bridge system is not registered.
 */
@PublishedApi
internal fun TestSystem.bridge(): BridgeSystem<*> = getOrNone<BridgeSystem<*>>().getOrElse {
  throw SystemNotRegisteredException(BridgeSystem::class)
}

/**
 * Returns the bridge system associated with the test system.
 *
 * @receiver the test system.
 * @return the bridge system.
 * @throws SystemNotRegisteredException if the bridge system is not registered.
 */
@StoveDsl
fun <T : Any> WithDsl.bridge(of: BridgeSystem<T>): TestSystem = this.testSystem.withBridgeSystem(of)

/**
 * Executes the specified block using the resolved bean from the bridge system.
 * Resolved beans are using physical components of the application.
 *
 * Suggested usage: validating or preparing the application state without accessing the physical components directly.
 * If you need to capture values from inside the block, declare variables outside and assign inside:
 *
 * ```kotlin
 *  TestSystem.validate {
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
@StoveDsl
inline fun <reified T : Any> ValidationDsl.using(
  block: @StoveDsl T.() -> Unit
): Unit = this.testSystem.bridge().using(block)

/**
 * Executes the specified block using two resolved beans.
 *
 * @param T1 the type of the first bean to resolve.
 * @param T2 the type of the second bean to resolve.
 * @param validation the block to execute with the resolved beans.
 */
@StoveDsl
inline fun <
  reified T1 : Any,
  reified T2 : Any
> ValidationDsl.using(validation: (T1, T2) -> Unit): Unit = testSystem.bridge().let {
  val t1: T1 = it.resolve()
  val t2: T2 = it.resolve()
  validation(t1, t2)
}

/**
 * Executes the specified block using three resolved beans.
 *
 * @param T1 the type of the first bean to resolve.
 * @param T2 the type of the second bean to resolve.
 * @param T3 the type of the third bean to resolve.
 * @param validation the block to execute with the resolved beans.
 */
@StoveDsl
inline fun <
  reified T1 : Any,
  reified T2 : Any,
  reified T3 : Any
> ValidationDsl.using(validation: (T1, T2, T3) -> Unit): Unit = this.testSystem
  .bridge()
  .let {
    val t1: T1 = it.resolve()
    val t2: T2 = it.resolve()
    val t3: T3 = it.resolve()
    validation(t1, t2, t3)
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
@StoveDsl
inline fun <
  reified T1 : Any,
  reified T2 : Any,
  reified T3 : Any,
  reified T4 : Any
> ValidationDsl.using(validation: (T1, T2, T3, T4) -> Unit): Unit = this.testSystem
  .bridge()
  .let {
    val t1: T1 = it.resolve()
    val t2: T2 = it.resolve()
    val t3: T3 = it.resolve()
    val t4: T4 = it.resolve()
    validation(t1, t2, t3, t4)
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
@StoveDsl
inline fun <
  reified T1 : Any,
  reified T2 : Any,
  reified T3 : Any,
  reified T4 : Any,
  reified T5 : Any
> ValidationDsl.using(validation: (T1, T2, T3, T4, T5) -> Unit): Unit = this.testSystem
  .bridge()
  .let {
    val t1: T1 = it.resolve()
    val t2: T2 = it.resolve()
    val t3: T3 = it.resolve()
    val t4: T4 = it.resolve()
    val t5: T5 = it.resolve()
    validation(t1, t2, t3, t4, t5)
  }
