package com.trendyol.stove.events

import arrow.core.Some
import com.trendyol.stove.system.abstractions.PluggedSystem
import com.trendyol.stove.system.abstractions.SystemKey
import com.trendyol.stove.system.annotations.StoveDsl

@StoveDsl
class StoveEventsDsl internal constructor() {
  internal var handlers = emptyList<StoveEventHandler>()
    private set

  @PublishedApi
  internal fun register(handler: StoveEventHandler) {
    handlers += handler
  }

  inline fun <reified E : StoveEvent> observe(crossinline handler: EventHandler<E>) {
    register { event -> if (event is E) handler(event) }
  }

  /** Omitting the key observes every registration matching the system type. */
  inline fun <reified T : PluggedSystem> onSystemStarted(crossinline handler: EventHandler<SystemStarted>) {
    observe<SystemStarted> { event ->
      if (T::class.java.isAssignableFrom(event.systemType)) handler(event)
    }
  }

  inline fun <reified T : PluggedSystem> onSystemStarted(key: SystemKey, crossinline handler: EventHandler<SystemStarted>) {
    onSystemStarted<T> { event -> if (event.key == Some(key)) handler(event) }
  }

  inline fun <reified T : PluggedSystem> onSystemStopped(crossinline handler: EventHandler<SystemStopped>) {
    observe<SystemStopped> { event -> if (T::class.java.isAssignableFrom(event.systemType)) handler(event) }
  }

  inline fun <reified T : PluggedSystem> onSystemStopped(key: SystemKey, crossinline handler: EventHandler<SystemStopped>) {
    onSystemStopped<T> { event -> if (event.key == Some(key)) handler(event) }
  }

  inline fun <reified T : PluggedSystem> onSystemFailed(crossinline handler: EventHandler<SystemFailed>) {
    observe<SystemFailed> { event -> if (T::class.java.isAssignableFrom(event.systemType)) handler(event) }
  }

  inline fun <reified T : PluggedSystem> onSystemFailed(key: SystemKey, crossinline handler: EventHandler<SystemFailed>) {
    onSystemFailed<T> { event -> if (event.key == Some(key)) handler(event) }
  }
}
