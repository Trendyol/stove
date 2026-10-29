package com.trendyol.stove.dashboard

import io.pyroscope.javaagent.ProfilerDelegate
import io.pyroscope.javaagent.api.Exporter
import io.pyroscope.javaagent.api.ProfilingScheduler
import java.time.Duration
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** Flushes even runs shorter than the interval, and serializes capture with shutdown. */
internal class FlushingProfileScheduler(
  private val interval: Duration,
  private val exporter: Exporter
) : ProfilingScheduler {
  private val logger = org.slf4j.LoggerFactory.getLogger(FlushingProfileScheduler::class.java)
  private val executor = Executors.newSingleThreadScheduledExecutor { task ->
    Thread(task, "stove-profiling").apply { isDaemon = true }
  }
  private val lifecycleLock = ReentrantLock()
  private var profiler: ProfilerDelegate? = null
  private var started = Instant.now()
  private var job: ScheduledFuture<*>? = null
  private var closed = false

  override fun start(profiler: ProfilerDelegate): Unit = lifecycleLock.withLock {
    check(!closed && this.profiler == null)
    profiler.start()
    this.profiler = profiler
    started = Instant.now()
    job = executor.scheduleAtFixedRate({ capture(restart = true) }, interval.toMillis(), interval.toMillis(), TimeUnit.MILLISECONDS)
  }

  internal fun capture(restart: Boolean): Unit = lifecycleLock.withLock {
    if (restart && closed) return@withLock
    val active = profiler ?: return@withLock
    try {
      active.stop()
      val end = Instant.now()
      val snapshot = try {
        active.dumpProfile(started, end)
      } finally {
        profiler = null
        if (restart) {
          active.start()
          profiler = active
          started = Instant.now()
        }
      }
      exporter.export(snapshot)
    } catch (error: Exception) {
      logger.warn("Unable to collect or upload Stove profiling evidence", error)
    } finally {
      if (!restart) profiler = null
    }
  }

  override fun stop(): Unit = lifecycleLock.withLock {
    if (closed) return@withLock
    closed = true
    job?.cancel(false)
    executor.shutdown()
    capture(restart = false)
  }
}
