package com.trendyol.stove.dashboard

import com.trendyol.stove.dashboard.api.DashboardEvent
import com.trendyol.stove.dashboard.api.RunStartedEvent
import java.nio.file.Path

/** Intentionally bypasses close and shutdown hooks to exercise crash recovery. */
object SpoolProcessFixture {
  @JvmStatic
  fun main(args: Array<String>) {
    val spool = DashboardSpool(DashboardIngestion.Grpc(), DashboardSpoolOptions(Path.of(args[0])))
    val lease = spool.tryAcquireDelivery()
    if (args.getOrNull(1) == "probe") {
      println(lease != null)
    } else {
      repeat(10) {
        spool.append(
          DashboardEvent.newBuilder().setRunId("crashed-run")
            .setRunStarted(RunStartedEvent.newBuilder().setAppName("crash-test")).build()
        )
      }
      spool.peek().forEach { println(it.event.eventId) }
    }
    System.out.flush()
    Runtime.getRuntime().halt(0)
  }
}
