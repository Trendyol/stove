package com.trendyol.stove.chaos

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** Registers compensation before activation and preserves the primary failure, including cancellation. */
internal suspend fun <T> restoring(restore: suspend () -> Unit, block: suspend () -> T): T {
  val result = try {
    block()
  } catch (failure: Throwable) {
    try {
      withContext(NonCancellable) { restore() }
    } catch (cleanupFailure: Throwable) {
      throw failure.also { if (cleanupFailure !== it) it.addSuppressed(cleanupFailure) }
    }
    throw failure
  }
  withContext(NonCancellable) { restore() }
  return result
}
