package com.trendyol.stove.reporting

import kotlinx.coroutines.currentCoroutineContext
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * Coroutine context element that identifies the current test.
 * Used by Kotest to correlate report entries with the test that generated them.
 */
data class StoveTestContext(
    val testId: String,
    val testName: String,
    val specName: String? = null
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<StoveTestContext>
}

/**
 * Extension function to get the current test context from the coroutine context.
 */
suspend fun currentStoveTestContext(): StoveTestContext? =
    currentCoroutineContext()[StoveTestContext]
