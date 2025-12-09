package com.trendyol.stove.testing.e2e

import com.trendyol.stove.testing.e2e.system.annotations.StoveDsl
import org.springframework.beans.factory.BeanRegistrarDsl
import org.springframework.boot.SpringApplication
import org.springframework.context.ApplicationContextInitializer
import org.springframework.context.support.GenericApplicationContext

@StoveDsl
fun stoveSpringRegistrar(
  registration: BeanRegistrarDsl.() -> Unit
): ApplicationContextInitializer<*> = ApplicationContextInitializer<GenericApplicationContext> { context ->
  context.register(BeanRegistrarDsl(registration))
}

@StoveDsl
fun SpringApplication.addTestDependencies(
  registration: BeanRegistrarDsl.() -> Unit
): Unit = this.addInitializers(stoveSpringRegistrar(registration))
