package com.trendyol.stove.dashboard

import java.nio.file.Files

internal fun isolatedEmitter(
  ingestion: DashboardIngestion,
  maxFailures: Int = 5,
  drainWarningIntervalMs: Long = 30000
): DashboardEmitter = DashboardEmitter(
  ingestion,
  maxFailures,
  drainWarningIntervalMs,
  DashboardSpoolOptions(Files.createTempDirectory("stove-emitter-test"))
)
