package com.trendyol.stove.reporting

/**
 * Thread-local holder for test context.
 * Used by JUnit 5 (which uses threads) to correlate report entries with tests.
 */
object StoveTestContextHolder {
  private val threadLocalContext = ThreadLocal<StoveTestContext>()

  /**
   * Set the current test context for this thread.
   */
  fun set(context: StoveTestContext) = threadLocalContext.set(context)

  /**
   * Get the current test context for this thread, if any.
   */
  fun get(): StoveTestContext? = threadLocalContext.get()

  /**
   * Clear the test context for this thread.
   */
  fun clear() = threadLocalContext.remove()
}
