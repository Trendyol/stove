package com.trendyol.stove.testing.e2e

import com.trendyol.stove.testing.e2e.system.annotations.StoveDsl
import org.springframework.boot.SpringApplication
import org.springframework.context.ApplicationContextInitializer
import org.springframework.context.support.*

/**
 * Creates an [ApplicationContextInitializer] that registers beans using the [BeanDefinitionDsl].
 *
 * This is the recommended way to register test dependencies in Spring Boot 3.x applications.
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
