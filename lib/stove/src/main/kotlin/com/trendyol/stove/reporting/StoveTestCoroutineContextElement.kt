package com.trendyol.stove.reporting

import kotlinx.coroutines.ThreadContextElement
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * Propagates Stove's test context across coroutine dispatcher switches.
 *
 * MDC is thread-local too, so callers can either let this element install Stove's
 * MDC keys or combine it with kotlinx.coroutines.slf4j.MDCContext when they need
 * a full MDC snapshot.
 */
class StoveTestCoroutineContextElement(
  private val ctx: StoveTestContext,
  private val includeMdc: Boolean = true
) : AbstractCoroutineContextElement(Key),
  ThreadContextElement<StoveTestCoroutineContextElement.PreviousState> {
  companion object Key : CoroutineContext.Key<StoveTestCoroutineContextElement>

  data class PreviousState(
    val testContext: StoveTestContext?,
    val mdc: Map<String, String>?
  )

  override fun updateThreadContext(context: CoroutineContext): PreviousState {
    val previous = PreviousState(
      testContext = StoveTestContextHolder.get(),
      mdc = if (includeMdc) StoveMdc.install(ctx) else null
    )
    StoveTestContextHolder.set(ctx)
    return previous
  }

  override fun restoreThreadContext(context: CoroutineContext, oldState: PreviousState) {
    oldState.testContext?.let(StoveTestContextHolder::set) ?: StoveTestContextHolder.clear()
    if (includeMdc) {
      StoveMdc.restore(oldState.mdc)
    }
  }
}
