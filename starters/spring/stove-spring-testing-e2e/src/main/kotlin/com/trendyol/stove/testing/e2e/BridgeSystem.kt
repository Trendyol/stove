package com.trendyol.stove.testing.e2e

import arrow.core.getOrElse
import com.trendyol.stove.testing.e2e.system.TestSystem
import com.trendyol.stove.testing.e2e.system.ValidationDsl
import com.trendyol.stove.testing.e2e.system.WithDsl
import com.trendyol.stove.testing.e2e.system.abstractions.AfterRunAwareWithContext
import com.trendyol.stove.testing.e2e.system.abstractions.PluggedSystem
import com.trendyol.stove.testing.e2e.system.abstractions.SystemNotRegisteredException
import org.springframework.beans.factory.getBean
import org.springframework.context.ApplicationContext

/**
 * A system that provides a bridge between the test system and the application context.
 *
 * @property testSystem the test system to bridge.
 */
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
inline fun <reified T : Any> ValidationDsl.using(validation: T.() -> Unit): Unit = this.testSystem.bridge().using(validation)
