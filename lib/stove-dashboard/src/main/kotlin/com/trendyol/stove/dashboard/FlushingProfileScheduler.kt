package com.trendyol.stove.dashboard

import io.pyroscope.javaagent.ProfilerDelegate
import io.pyroscope.javaagent.Snapshot
import io.pyroscope.javaagent.api.Exporter
import io.pyroscope.javaagent.api.ProfilingScheduler
import java.time.Duration
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** Serializes native capture while keeping network exports outside the lifecycle lock. */
internal class FlushingProfileScheduler(
  private val interval: Duration,
  exporter: Exporter,
  private val shutdownTimeout: Duration = Duration.ofSeconds(5)
) : ProfilingScheduler {
  private val logger = org.slf4j.LoggerFactory.getLogger(FlushingProfileScheduler::class.java)
  private val executor = Executors.newSingleThreadScheduledExecutor { task ->
    Thread(task, "stove-profiling").apply { isDaemon = true }
  }

  private val exports = ProfileExportQueue(exporter)
  private val lifecycleLock = ReentrantLock()
  private var profiler: ProfilerDelegate? = null
  private var running = false
  private var started = Instant.now()
  private var job: ScheduledFuture<*>? = null
  private var closed = false

  override fun start(profiler: ProfilerDelegate): Unit = lifecycleLock.withLock {
    check(!closed && this.profiler == null)
    profiler.start()
    this.profiler = profiler
    running = true
    started = Instant.now()
    job = executor.scheduleWithFixedDelay({ capture(restart = true) }, interval.toMillis(), interval.toMillis(), TimeUnit.MILLISECONDS)
  }

  internal fun capture(restart: Boolean): Unit = lifecycleLock.withLock {
    if (restart && closed) return@withLock
    val active = profiler ?: return@withLock
    val snapshot = try {
      collect(active)
    } finally {
      if (restart && !running) restartProfiler(active)
    }
    snapshot?.let(exports::submit)
    if (!restart) profiler = null
  }

  // These helpers are only called while holding lifecycleLock.
  private fun collect(active: ProfilerDelegate): Snapshot? = try {
    if (running) {
      active.stop()
      running = false
      active.dumpProfile(started, Instant.now())
    } else {
      null
    }
  } catch (error: Exception) {
    logger.warn("Unable to collect Stove profiling evidence", error)
    null
  }

  private fun restartProfiler(active: ProfilerDelegate) {
    try {
      active.start()
      running = true
      started = Instant.now()
    } catch (error: Exception) {
      // Keep the delegate so the next capture retries startup.
      logger.warn("Unable to restart Stove profiler; will retry next interval", error)
    }
  }

  override fun stop() {
    lifecycleLock.withLock {
      if (closed) return
      closed = true
      job?.cancel(false)
      executor.shutdown()
      capture(restart = false)
      exports.shutdown()
    }
    exports.awaitTermination(shutdownTimeout)
  }
}
