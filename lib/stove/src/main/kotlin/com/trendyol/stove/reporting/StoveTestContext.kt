package com.trendyol.stove.reporting

import kotlinx.coroutines.ThreadContextElement
import kotlinx.coroutines.currentCoroutineContext
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * Coroutine context element that identifies the current test.
 * Used by Kotest to correlate report entries with the test that generated them.
 *
 * It is also a [ThreadContextElement]: it mirrors itself into [StoveTestContextHolder]
 * on every coroutine resumption and restores the previous value on suspension. This keeps
 * non-suspend resolvers (e.g. [StoveReporter.currentTestId], called by systems at record
 * time) correct even when a coroutine hops dispatcher worker threads. Without it, a
 * suspending system op resuming on another thread (Kotest's `DefaultDispatcher-worker-N`)
 * loses the [ThreadLocal] and entries get stamped with the default test id.
 */
data class StoveTestContext(
    val testId: String,
    val testName: String,
    val specName: String? = null,
    val testPath: List<String> = emptyList()
) : AbstractCoroutineContextElement(Key),
    ThreadContextElement<StoveTestContext?> {
    companion object Key : CoroutineContext.Key<StoveTestContext>

    override fun updateThreadContext(context: CoroutineContext): StoveTestContext? {
        val previous = StoveTestContextHolder.get()
        StoveTestContextHolder.set(this)
        return previous
    }

    override fun restoreThreadContext(context: CoroutineContext, oldState: StoveTestContext?) {
        if (oldState == null) {
            StoveTestContextHolder.clear()
        } else {
            StoveTestContextHolder.set(oldState)
        }
    }
}

/**
 * Extension function to get the current test context from the coroutine context.
 */
suspend fun currentStoveTestContext(): StoveTestContext? =
    currentCoroutineContext()[StoveTestContext]
