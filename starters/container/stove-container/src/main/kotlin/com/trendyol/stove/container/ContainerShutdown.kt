package com.trendyol.stove.container

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.slf4j.LoggerFactory
import org.testcontainers.containers.GenericContainer
import java.util.concurrent.FutureTask
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Docker transport calls may ignore interruption. Bound both caller wait and abandoned workers. */
internal object ContainerShutdown {
  private val logger = LoggerFactory.getLogger(ContainerShutdown::class.java)
  private val workers = ThreadPoolExecutor(
    0,
    4,
    30,
    TimeUnit.SECONDS,
    SynchronousQueue(),
    { task -> Thread(task, "stove-container-shutdown").apply { isDaemon = true } }
  )

  suspend fun stop(container: GenericContainer<*>, gracefulShutdownTimeout: Duration, onRemoved: () -> Unit) {
    var cancellation: CancellationException? = null
    try {
      run(gracefulShutdownTimeout) {
        container.containerId?.let { id ->
          val seconds = kotlin.math.ceil(gracefulShutdownTimeout.inWholeMilliseconds / 1000.0)
            .toInt().coerceAtLeast(1)
          container.dockerClient.stopContainerCmd(id).withTimeout(seconds).exec()
        }
      }
    } catch (error: TimeoutCancellationException) {
      if (!currentCoroutineContext().isActive) {
        cancellation = error
        throw error
      }
      logger.warn("Graceful container shutdown timed out; removing container", error)
    } catch (error: CancellationException) {
      cancellation = error
      throw error
    } catch (error: Exception) {
      logger.warn("Graceful container shutdown failed; removing container", error)
    } finally {
      // Cleanup gets its own bounded attempt even when the caller was cancelled.
      withContext(NonCancellable) {
        try {
          run(5.seconds) { container.stop() }
          onRemoved()
        } catch (error: Exception) {
          // Preserve the handle so a subsequent stop can retry cleanup.
          logger.warn("Container cleanup failed; retaining container for a later cleanup attempt", error)
          if (cancellation == null) throw error
        }
      }
    }
    currentCoroutineContext().ensureActive()
  }

  private suspend fun run(timeout: Duration, action: () -> Unit): Unit = withTimeout(timeout) {
    suspendCancellableCoroutine { continuation ->
      val task = FutureTask {
        try {
          action()
          continuation.resume(Unit)
        } catch (error: Throwable) {
          continuation.resumeWithException(error)
        }
      }
      continuation.invokeOnCancellation { task.cancel(true) }
      try {
        workers.execute(task)
      } catch (error: Exception) {
        continuation.resumeWithException(error)
      }
    }
  }
}
