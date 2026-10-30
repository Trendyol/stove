package com.trendyol.stove.dashboard

import com.sun.net.httpserver.HttpServer
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.pyroscope.http.Format
import io.pyroscope.javaagent.EventType
import io.pyroscope.javaagent.ProfilerDelegate
import io.pyroscope.javaagent.Snapshot
import io.pyroscope.javaagent.api.Exporter
import io.pyroscope.javaagent.config.Config
import io.pyroscope.labels.pb.JfrLabels
import java.net.InetSocketAddress
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList

class DashboardProfilingTest : FunSpec({
  test("shutdown flushes a partial interval once and dump failure does not stop later captures") {
    val profiler = FakeProfiler()
    val snapshots = mutableListOf<Snapshot>()
    val exporter = object : Exporter {
      override fun export(snapshot: Snapshot) {
        snapshots.add(snapshot)
      }
      override fun stop() = Unit
    }
    val scheduler = FlushingProfileScheduler(Duration.ofHours(1), exporter)
    scheduler.start(profiler)
    profiler.failDump = true
    scheduler.capture(restart = true)
    profiler.running shouldBe true
    profiler.failDump = false
    scheduler.stop()
    scheduler.stop()
    profiler.running shouldBe false
    snapshots.size shouldBe 1
    snapshots.single().ended.isBefore(snapshots.single().started) shouldBe false
  }

  test("native agent uploads final JFR with the dashboard run identity") {
    val requests = CopyOnWriteArrayList<Triple<String, String, ByteArray>>()
    val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    server.createContext("/ingest") { exchange ->
      requests.add(Triple(exchange.requestHeaders.getFirst("X-Stove-Run-Id"), exchange.requestURI.toString(), exchange.requestBody.readAllBytes()))
      exchange.sendResponseHeaders(200, -1)
      exchange.close()
    }
    server.start()
    val properties = mapOf(
      "stove.profiling.server" to "http://127.0.0.1:${server.address.port}",
      "stove.profiling.interval" to "3600"
    )
    val previous = properties.mapValues { System.getProperty(it.key) }
    try {
      properties.forEach { (key, value) -> System.setProperty(key, value) }
      DashboardProfiling("profile-run", "checkout").use {
        val deadline = System.nanoTime() + 300_000_000
        var counter = 0L
        while (System.nanoTime() < deadline) counter++
        (counter > 0) shouldBe true
      }
      requests.size shouldBe 1
      requests.single().first shouldBe "profile-run"
      requests.single().second shouldContain "format=jfr"
      requests.single().third.toString(Charsets.ISO_8859_1) shouldContain "name=\"jfr\""
    } finally {
      previous.forEach { (key, value) -> if (value == null) System.clearProperty(key) else System.setProperty(key, value) }
      server.stop(0)
    }
  }
})

private class FakeProfiler : ProfilerDelegate {
  var running = false
  var failDump = false
  override fun start() {
    running = true
  }
  override fun stop() {
    running = false
  }
  override fun setConfig(config: Config) = Unit
  override fun dumpProfile(start: Instant, end: Instant): Snapshot {
    if (failDump) error("dump failed")
    return Snapshot(Format.JFR, EventType.ITIMER, start, end, byteArrayOf(), JfrLabels.LabelsSnapshot.getDefaultInstance())
  }
}
