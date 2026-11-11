package com.trendyol.stove.system

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * Binds the validation DSL to a Stove instance for a coroutine and its children.
 * This does not start or stop the application, or replace Stove's suite-level lifecycle.
 */
class StoveContext(val stove: Stove) : AbstractCoroutineContextElement(Key) {
  companion object Key : CoroutineContext.Key<StoveContext> {
    /** Captures the initialized suite instance before entering a test coroutine. */
    fun capture(): StoveContext {
      check(Stove.instanceInitialized()) { "Stove is not initialized yet, do not forget to call Stove#run" }
      return StoveContext(Stove.instance)
    }
  }
}
