package com.trendyol.stove.quarkus

import java.util.concurrent.atomic.AtomicReference
import java.util.function.BiConsumer

// Use Quarkus's own startup notification under its monitor, including across fast-jar classloaders.
// The notification is published only after Application.doStart has completed.
internal class QuarkusRuntimeLifecycle(classLoader: ClassLoader) {
  private val notification = classLoader.loadClass("io.quarkus.dev.appstate.ApplicationStateNotification")
  private val getState = notification.getMethod("getState")
  private val lifecycleManager = classLoader.loadClass("io.quarkus.runtime.ApplicationLifecycleManager")
  private val setExitHandler = lifecycleManager.getMethod("setDefaultExitCodeHandler", BiConsumer::class.java)

  @Volatile private var launched = false

  fun isStarted(): Boolean = launched && synchronized(notification) {
    getState.invoke(null).toString() == "STARTED"
  }

  fun run(launch: () -> Unit) {
    notification.getMethod("reset").invoke(null)
    val previousExitHandler = lifecycleManager.getMethod("getDefaultExitCodeHandler").invoke(null)
    val exitFailure = AtomicReference<Throwable?>(null)
    // Quarkus normally calls System.exit; keep failures and orderly shutdown inside the test JVM.
    val exitHandler = BiConsumer<Int, Throwable?> { code, cause ->
      if (cause != null || code != 0) {
        exitFailure.compareAndSet(null, cause ?: IllegalStateException("Quarkus exited with code $code"))
      }
    }
    setExitHandler.invoke(null, exitHandler)
    launched = true
    try {
      launch()
      exitFailure.get()?.let { throw it }
    } finally {
      launched = false
      setExitHandler.invoke(null, previousExitHandler)
    }
  }
}
