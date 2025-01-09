package com.trendyol.stove.testing

import com.trendyol.stove.testing.e2e.system.annotations.StoveDsl
import io.micronaut.context.ApplicationContext
import io.micronaut.context.event.ApplicationEventListener
import io.micronaut.context.event.StartupEvent
import io.micronaut.runtime.Micronaut

@StoveDsl
abstract class BaseApplicationContextInitializer : ApplicationEventListener<StartupEvent> {

    private val registrations = mutableListOf<(ApplicationContext) -> Unit>()

    @StoveDsl
    fun register(registration: (ApplicationContext) -> Unit): BaseApplicationContextInitializer {
        registrations.add(registration)
        return this
    }

    override fun onApplicationEvent(event: StartupEvent) {
        val applicationContext = event.source as ApplicationContext
        onStartup(applicationContext)
        registrations.forEach { it(applicationContext) }
    }

    protected open fun onStartup(applicationContext: ApplicationContext) {
    }

    companion object {
        @JvmStatic fun main(args: Array<String>) {
            Micronaut.build()
                .args(*args)
                .start()
        }
    }
}
