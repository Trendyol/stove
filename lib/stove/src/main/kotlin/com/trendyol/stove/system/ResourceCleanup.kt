package com.trendyol.stove.system

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** Attempts every cleanup in order, even during cancellation; later failures are suppressed on the first. */
suspend fun closeAll(vararg actions: suspend () -> Unit): Unit = closeAll(actions.asList()) { it() }

/** Closes resources in iteration order without abandoning later resources when one fails. */
@Suppress("TooGenericExceptionCaught")
suspend fun <T> closeAll(resources: Iterable<T>, close: suspend (T) -> Unit) {
  val failure = withContext(NonCancellable) {
    var primary: Throwable? = null
    resources.forEach { resource ->
      try {
        close(resource)
      } catch (failure: Throwable) {
        if (primary == null) {
          primary = failure
        } else {
          primary.addSuppressed(failure)
        }
      }
    }
    primary
  }
  // Throw outside the context boundary so coroutine stacktrace recovery preserves suppressed errors.
  failure?.let { throw it }
}
