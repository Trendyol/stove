package com.trendyol.stove.dashboard

import io.pyroscope.http.Format
import io.pyroscope.javaagent.EventType
import io.pyroscope.javaagent.PyroscopeAgent
import io.pyroscope.javaagent.config.Config
import io.pyroscope.javaagent.impl.DefaultLogger
import io.pyroscope.javaagent.impl.PyroscopeExporter
import java.time.Duration
import java.util.UUID

/** Loaded only when the tracing plugin explicitly enables profiling. */
internal class DashboardProfiling(runId: String, appName: String) : AutoCloseable {
  init {
    check(!PyroscopeAgent.isStarted()) { "A profiler is already active in this JVM; Stove requires ownership of its profiling lifecycle" }
  }
  private val service = System.getProperty("stove.profiling.service", appName)
  private val config = Config.Builder()
    .setAgentEnabled(true)
    .setApplicationName(service)
    .setServerAddress(System.getProperty("stove.profiling.server", "http://localhost:4040"))
    .setFormat(Format.JFR)
    .setProfilingEvent(EventType.ITIMER)
    .setProfilingAlloc(System.getProperty("stove.profiling.alloc", ""))
    .setProfilingLock(System.getProperty("stove.profiling.lock", ""))
    .setAllocLive(System.getProperty("stove.profiling.live", "false").toBoolean())
    .setGcBeforeDump(false)
    .setJavaStackDepthMax(256)
    .setUploadInterval(Duration.ofSeconds(System.getProperty("stove.profiling.interval", "10").toLong()))
    .setProfileExportTimeout(Duration.ofSeconds(5))
    .setIngestMaxRetries(2)
    .setLabels(mapOf("stove_app" to appName, "stove_run_id" to runId))
    .setHTTPHeaders(
      mapOf(
        "X-Stove-Run-Id" to runId,
        "X-Stove-Service-Name" to service,
        "X-Stove-Instance-Id" to UUID.randomUUID().toString()
      )
    )
    .build()
  private val exporter = PyroscopeExporter(config, DefaultLogger(config.logLevel, System.err))
  private val scheduler = FlushingProfileScheduler(config.uploadInterval, exporter)

  init {
    try {
      PyroscopeAgent.start(PyroscopeAgent.Options.Builder(config).setExporter(exporter).setScheduler(scheduler).build())
      check(PyroscopeAgent.isStarted()) { "Pyroscope could not start on this JVM" }
    } catch (error: Throwable) {
      scheduler.stop()
      exporter.stop()
      throw error
    }
  }

  override fun close() {
    if (PyroscopeAgent.isStarted()) PyroscopeAgent.stop()
  }
}
