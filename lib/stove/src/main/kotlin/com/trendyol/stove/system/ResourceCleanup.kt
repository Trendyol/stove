package com.trendyol.stove.system

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * Attempts every cleanup action in order, even during cancellation. The first failure remains primary;
 * subsequent failures are suppressed. Each action should own one resource or cleanup callback.
 */
suspend fun closeAll(vararg actions: suspend () -> Unit): Unit = closeAll(actions.asList()) { it() }

/** Closes every resource in iteration order, preserving the first failure and suppressing later ones. */
suspend fun <T> closeAll(resources: Iterable<T>, close: suspend (T) -> Unit) {
  val failure = withContext(NonCancellable) {
    var primary: Throwable? = null
    resources.forEach { resource ->
      try {
        close(resource)
      } catch (failure: Throwable) {
        if (primary == null) {
          primary = failure
        } else if (primary !== failure) {
          primary.addSuppressed(failure)
        }
      }
    }
    primary
  }
  // Throw outside the context boundary so coroutine stacktrace recovery cannot drop suppressed errors.
  failure?.let { throw it }
}
