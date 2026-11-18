package com.trendyol.stove.dashboard

import com.fasterxml.jackson.databind.ObjectMapper
import com.trendyol.stove.dashboard.api.SnapshotEvent
import com.trendyol.stove.events.SnapshotRecorded
import org.slf4j.LoggerFactory

private val snapshotMapper = ObjectMapper()

internal fun SnapshotRecorded.toProto(): SnapshotEvent = SnapshotEvent.newBuilder()
  .setTestId(testId).setSystem(snapshot.system)
  .setStateJson(
    runCatching { snapshotMapper.writeValueAsString(snapshot.state) }
      .onFailure { LoggerFactory.getLogger("DashboardSnapshots").warn("Snapshot serialization failed for ${snapshot.system}", it) }
      .getOrDefault("{}")
  )
  .setSummary(snapshot.summary).setTrigger(trigger.name)
  .setTimestamp(capturedAt.toTimestamp()).build()
