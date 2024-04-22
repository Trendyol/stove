package com.trendyol.stove.testing.e2e

import com.trendyol.stove.testing.e2e.system.annotations.StoveDsl
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.*
import org.springframework.context.support.*

@StoveDsl
abstract class BaseApplicationContextInitializer(registration: BeanDefinitionDsl.() -> Unit = {}) :
  ApplicationContextInitializer<GenericApplicationContext> {
  private var registrations = mutableListOf<(BeanDefinitionDsl) -> Unit>()
  private val beans = beans {}

  init {
    registrations.add(registration)
  }

  @StoveDsl
  protected fun register(registration: BeanDefinitionDsl.() -> Unit): BaseApplicationContextInitializer {
    registrations.add(registration)
    return this
  }

  override fun initialize(applicationContext: GenericApplicationContext) {
    applicationContext.addApplicationListener { event ->
      when (event) {
        is ApplicationReadyEvent -> applicationReady(event.applicationContext as GenericApplicationContext)
        else -> onEvent(event)
      }
    }
    beans.initialize(applicationContext)
    registrations.forEach { it(beans) }
  }

  protected open fun applicationReady(applicationContext: GenericApplicationContext) {}

  protected open fun onEvent(event: ApplicationEvent) {}
}
