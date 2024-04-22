package com.trendyol.stove.testing.e2e

import arrow.core.getOrElse
import com.trendyol.stove.testing.e2e.system.*
import com.trendyol.stove.testing.e2e.system.abstractions.*
import com.trendyol.stove.testing.e2e.system.annotations.StoveDsl
import org.springframework.beans.factory.getBean
import org.springframework.context.ApplicationContext

/**
 * A system that provides a bridge between the test system and the application context.
 *
 * @property testSystem the test system to bridge.
 */
@StoveDsl
class BridgeSystem(override val testSystem: TestSystem) : PluggedSystem, AfterRunAwareWithContext<ApplicationContext> {
  /**
   * The application context used to resolve dependencies.
   */
  lateinit var ctx: ApplicationContext

  /**
   * Closes the bridge system.
   */
  override fun close(): Unit = Unit

  /**
   * Initializes the bridge system after the test run.
   *
   * @param context the application context.
   */
  override suspend fun afterRun(context: ApplicationContext) {
    ctx = context
  }

  /**
   * Resolves a bean of the specified type from the application context.
   *
   * @param T the type of bean to resolve.
   * @return the resolved bean.
   */
  @PublishedApi
  internal inline fun <reified T : Any> resolve(): T = ctx.getBean()

  /**
   * Executes the specified validation function using the resolved bean.
   *
   * @param T the type of object being validated.
   * @param validation the validation function to apply to the object.
   */
  @StoveDsl
  inline fun <reified T : Any> using(validation: T.() -> Unit): Unit = validation(resolve())
}

/**
 * Adds a bridge system to the test system and returns the modified test system.
 *
 * @receiver the test system to modify.
 * @return the modified test system.
 */
internal fun TestSystem.withBridgeSystem(): TestSystem = getOrRegister(BridgeSystem(this)).let { this }

/**
 * Returns the bridge system associated with the test system.
 * This function is only available in the validation DSL.
 *
 * @receiver the test system.
 * @return the bridge system.
 * @throws SystemNotRegisteredException if the bridge system is not registered.
 */
@PublishedApi
internal fun TestSystem.bridge(): BridgeSystem =
  getOrNone<BridgeSystem>().getOrElse { throw SystemNotRegisteredException(BridgeSystem::class) }

/**
 * Returns the bridge system associated with the test system.
 *
 * @receiver the test system.
 * @return the bridge system.
 * @throws SystemNotRegisteredException if the bridge system is not registered.
 */
@StoveDsl
fun WithDsl.bridge(): TestSystem = this.testSystem.withBridgeSystem()

/**
 * Executes the specified validation function using the resolved bean from the bridge system.
 * Resolved beans are using physical components of the application.
 *
 * Suggested usage: validating or preparing the application state without accessing the physical components directly.
 * ```kotlin
 *  TestSystem.validate {
 *      using<PersonService> {
 *          this.serviceName shouldBe "personService"
 *          this.find(userId = 123) shouldBe Person(id = 123, name = "John Doe")
 *      }
 *  }
 * ```
 *
 * @receiver the validation DSL.
 * @param T the type of object being validated.
 * @param validation the validation function to apply to the object.
 */
@StoveDsl
inline fun <reified T : Any> ValidationDsl.using(
  validation: @StoveDsl T.() -> Unit
): Unit = this.testSystem.bridge().using(validation)

@StoveDsl
inline fun <
  reified T1 : Any,
  reified T2 : Any
> ValidationDsl.using(validation: (T1, T2) -> Unit): Unit = testSystem.bridge().let {
  val t1: T1 = it.resolve()
  val t2: T2 = it.resolve()
  validation(t1, t2)
}

@StoveDsl
inline fun <
  reified T1 : Any,
  reified T2 : Any,
  reified T3 : Any
> ValidationDsl.using(validation: (T1, T2, T3) -> Unit): Unit = this.testSystem.bridge()
  .let {
    val t1: T1 = it.resolve()
    val t2: T2 = it.resolve()
    val t3: T3 = it.resolve()
    validation(t1, t2, t3)
  }

@StoveDsl
inline fun <
  reified T1 : Any,
  reified T2 : Any,
  reified T3 : Any,
  reified T4 : Any
> ValidationDsl.using(validation: (T1, T2, T3, T4) -> Unit): Unit = this.testSystem.bridge()
  .let {
    val t1: T1 = it.resolve()
    val t2: T2 = it.resolve()
    val t3: T3 = it.resolve()
    val t4: T4 = it.resolve()
    validation(t1, t2, t3, t4)
  }

@StoveDsl
inline fun <
  reified T1 : Any,
  reified T2 : Any,
  reified T3 : Any,
  reified T4 : Any,
  reified T5 : Any
> ValidationDsl.using(validation: (T1, T2, T3, T4, T5) -> Unit): Unit = this.testSystem.bridge()
  .let {
    val t1: T1 = it.resolve()
    val t2: T2 = it.resolve()
    val t3: T3 = it.resolve()
    val t4: T4 = it.resolve()
    val t5: T5 = it.resolve()
    validation(t1, t2, t3, t4, t5)
  }
