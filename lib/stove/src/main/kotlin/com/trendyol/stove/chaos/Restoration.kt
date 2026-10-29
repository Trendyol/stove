package com.trendyol.stove.chaos

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** Registers compensation before activation and preserves the primary failure, including cancellation. */
internal suspend fun <T> restoring(restore: suspend () -> Unit, block: suspend () -> T): T {
  var primary: Throwable? = null
  try {
    return block()
  } catch (failure: Throwable) {
    primary = failure
    throw failure
  } finally {
    val cleanupFailure = withContext(NonCancellable) {
      try {
        restore()
        null
      } catch (failure: Throwable) {
        failure
      }
    }
    if (cleanupFailure != null) {
      if (primary == null) throw cleanupFailure
      if (primary !== cleanupFailure) primary.addSuppressed(cleanupFailure)
    }
  }
}
