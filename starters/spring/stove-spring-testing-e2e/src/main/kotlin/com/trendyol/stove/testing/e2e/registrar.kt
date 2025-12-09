@file:Suppress("DEPRECATION")

package com.trendyol.stove.testing.e2e

import com.trendyol.stove.testing.e2e.system.annotations.StoveDsl
import org.springframework.beans.factory.BeanRegistrarDsl
import org.springframework.boot.SpringApplication
import org.springframework.context.ApplicationContextInitializer
import org.springframework.context.support.*

// =============================================================================
// Spring Boot 3.x (uses BeanDefinitionDsl - deprecated but still works)
// =============================================================================

/**
 * Creates an [ApplicationContextInitializer] that registers beans using the [BeanDefinitionDsl].
 *
 * **For Spring Boot 3.x applications.**
 *
 * Example usage:
 * ```kotlin
 * TestAppRunner.run(params) {
 *   addInitializers(
 *     stoveSpringRegistrar {
 *       bean<MyService>()
 *       bean<MyRepository> { MyRepositoryImpl() }
 *     }
 *   )
 * }
 * ```
 *
 * @param registration A lambda with [BeanDefinitionDsl] receiver to define beans.
 * @return An [ApplicationContextInitializer] that can be added to a [SpringApplication].
 */
@StoveDsl
fun stoveSpringRegistrar(
  registration: BeanDefinitionDsl.() -> Unit
): ApplicationContextInitializer<GenericApplicationContext> = ApplicationContextInitializer { context ->
  val beansDsl = beans(registration)
  beansDsl.initialize(context)
}

/**
 * Extension function to easily add test dependencies to a [SpringApplication].
 *
 * **For Spring Boot 3.x applications.**
 *
 * Example usage:
 * ```kotlin
 * TestAppRunner.run(params) {
 *   addTestDependencies {
 *     bean<MyService>()
 *     bean<MyRepository> { MyRepositoryImpl() }
 *   }
 * }
 * ```
 *
 * @param registration A lambda with [BeanDefinitionDsl] receiver to define beans.
 */
@StoveDsl
fun SpringApplication.addTestDependencies(
  registration: BeanDefinitionDsl.() -> Unit
): Unit = this.addInitializers(stoveSpringRegistrar(registration))

// =============================================================================
// Spring Boot 4.x (uses BeanRegistrarDsl - the new recommended approach)
// =============================================================================

/**
 * Creates an [ApplicationContextInitializer] that registers beans using the [BeanRegistrarDsl].
 *
 * **For Spring Boot 4.x applications.**
 *
 * Example usage:
 * ```kotlin
 * TestAppRunner.run(params) {
 *   addInitializers(
 *     stoveSpring4xRegistrar {
 *       registerBean<MyService>()
 *       registerBean<MyRepository> { MyRepositoryImpl() }
 *     }
 *   )
 * }
 * ```
 *
 * @param registration A lambda with [BeanRegistrarDsl] receiver to define beans.
 * @return An [ApplicationContextInitializer] that can be added to a [SpringApplication].
 */
@StoveDsl
fun stoveSpring4xRegistrar(
  registration: BeanRegistrarDsl.() -> Unit
): ApplicationContextInitializer<*> = ApplicationContextInitializer<GenericApplicationContext> { context ->
  context.register(BeanRegistrarDsl(registration))
}

/**
 * Extension function to easily add test dependencies to a [SpringApplication].
 *
 * **For Spring Boot 4.x applications.**
 *
 * Example usage:
 * ```kotlin
 * TestAppRunner.run(params) {
 *   addTestDependencies4x {
 *     registerBean<MyService>()
 *     registerBean<MyRepository> { MyRepositoryImpl() }
 *   }
 * }
 * ```
 *
 * @param registration A lambda with [BeanRegistrarDsl] receiver to define beans.
 */
@StoveDsl
fun SpringApplication.addTestDependencies4x(
  registration: BeanRegistrarDsl.() -> Unit
): Unit = this.addInitializers(stoveSpring4xRegistrar(registration))
