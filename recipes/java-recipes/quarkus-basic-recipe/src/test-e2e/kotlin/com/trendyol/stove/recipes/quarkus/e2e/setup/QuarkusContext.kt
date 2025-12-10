package com.trendyol.stove.recipes.quarkus.e2e.setup

/**
 * Manages the Quarkus runtime classloader context.
 *
 * Quarkus uses classloader isolation, meaning beans from the Quarkus runtime
 * are loaded by a different classloader than the test code. This object provides
 * utilities for working across the classloader boundary.
 */
object QuarkusContext {
  @Volatile
  private var _classLoader: ClassLoader? = null

  /**
   * The Quarkus runtime classloader, captured when Arc container becomes ready.
   */
  val classLoader: ClassLoader
    get() = _classLoader ?: error("Quarkus classloader not available. Is Quarkus running?")

  /**
   * Whether the Quarkus classloader has been captured.
   */
  val isReady: Boolean
    get() = _classLoader != null

  /**
   * Sets the Quarkus classloader. Called internally when Arc container is detected.
   */
  internal fun setClassLoader(loader: ClassLoader) {
    _classLoader = loader
  }

  /**
   * Resets the classloader reference. Called during cleanup.
   */
  internal fun reset() {
    _classLoader = null
  }

  /**
   * Executes an action with the Quarkus classloader as the thread's context classloader.
   * This is necessary for reflection calls to work properly with Quarkus beans.
   */
  inline fun <T> withContext(action: () -> T): T {
    val original = Thread.currentThread().contextClassLoader
    return try {
      Thread.currentThread().contextClassLoader = classLoader
      action()
    } finally {
      Thread.currentThread().contextClassLoader = original
    }
  }
}
