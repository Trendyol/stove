package com.trendyol.stove.dashboard

import arrow.core.getOrElse
import com.trendyol.stove.dashboard.api.RunEnvironmentEvent
import com.trendyol.stove.events.ComponentPhase
import com.trendyol.stove.events.RunComponent
import com.trendyol.stove.dashboard.api.ComponentPhase as ProtoPhase
import com.trendyol.stove.dashboard.api.RunComponent as ProtoComponent

internal fun List<RunComponent>.toEnvironmentProto(): RunEnvironmentEvent =
  RunEnvironmentEvent.newBuilder().addAllComponents(map { it.toProto() }).build()

private fun RunComponent.toProto(): ProtoComponent = ProtoComponent.newBuilder()
  .setId(id).setName(name).setType(type).setKey(key.getOrElse { "" }).setRole(role.name)
  .addAllPhases(phases.map { it.toProto() }).build()

private fun ComponentPhase.toProto(): ProtoPhase = ProtoPhase.newBuilder()
  .setName(name.name).setStatus(status.name).setStartedAt(startedAt.toString())
  .setDurationMs(durationMs).setError(error.getOrElse { "" }).build()
