package com.trendyol.stove.dashboard

import io.pyroscope.javaagent.Snapshot
import io.pyroscope.javaagent.api.Exporter
import java.time.Duration
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Owns serialized, bounded network exports independently of native capture. */
internal class ProfileExportQueue(private val exporter: Exporter) {
  private val logger = org.slf4j.LoggerFactory.getLogger(ProfileExportQueue::class.java)

  // One in-flight export and one pending snapshot bound memory during slow uploads.
  private val worker = ThreadPoolExecutor(
    1,
    1,
    0,
    TimeUnit.MILLISECONDS,
    ArrayBlockingQueue(1),
    { task -> Thread(task, "stove-profile-export").apply { isDaemon = true } },
    { task, worker ->
      worker.queue.poll()
      worker.queue.offer(task)
      logger.warn("Slow profile exporter: replaced pending snapshot with the newest recording")
    }
  )

  fun submit(snapshot: Snapshot) {
    worker.execute {
      try {
        exporter.export(snapshot)
      } catch (error: Exception) {
        logger.warn("Unable to upload Stove profiling evidence", error)
      }
    }
  }

  fun shutdown() {
    worker.shutdown()
  }

  fun awaitTermination(timeout: Duration) {
    try {
      if (!worker.awaitTermination(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
        worker.shutdownNow()
        logger.warn("Profile export did not finish within the shutdown timeout")
      }
    } catch (_: InterruptedException) {
      worker.shutdownNow()
      Thread.currentThread().interrupt()
    }
  }
}
