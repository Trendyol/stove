package com.trendyol.stove.testing.e2e

import org.springframework.boot.context.event.ApplicationContextInitializedEvent
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.ApplicationContextInitializer
import org.springframework.context.support.BeanDefinitionDsl
import org.springframework.context.support.GenericApplicationContext
import org.springframework.context.support.beans

abstract class BaseApplicationContextInitializer(registration: BeanDefinitionDsl.() -> Unit = {}) :
    ApplicationContextInitializer<GenericApplicationContext> {

    private var registrations = mutableListOf<(BeanDefinitionDsl) -> Unit>()
    private val beans = beans {}

    init {
        registrations.add(registration)
    }

    protected fun register(
        registration: BeanDefinitionDsl.() -> Unit,
    ): BaseApplicationContextInitializer {
        registrations.add(registration)
        return this
    }

    override fun initialize(applicationContext: GenericApplicationContext) {
        applicationContext.addApplicationListener {
            when (it) {
                is ApplicationReadyEvent ->
                    applicationReady(it.applicationContext as GenericApplicationContext)
                is ApplicationContextInitializedEvent ->
                    applicationReady(it.applicationContext as GenericApplicationContext)
            }
        }
        beans.initialize(applicationContext)
        registrations.forEach { it(beans) }
    }

    protected open fun applicationReady(applicationContext: GenericApplicationContext) {}
    protected open fun applicationContextInitialized(applicationContext: GenericApplicationContext) {}
}
