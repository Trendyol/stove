package com.trendyol.stove.dashboard

import arrow.core.getOrElse
import com.google.protobuf.Timestamp
import com.trendyol.stove.dashboard.api.*
import com.trendyol.stove.interactions.*
import com.trendyol.stove.reporting.ReportEntry
import com.trendyol.stove.tracing.SpanInfo
import java.time.Instant

internal fun ReportEntry.toProto(): EntryRecordedEvent = EntryRecordedEvent.newBuilder()
  .setTestId(testId)
  .setTimestamp(timestamp.toTimestamp())
  .setSystem(system)
  .setAction(action)
  .setResult(result.name)
  .setInput(input.getOrElse { "" }.toString())
  .setOutput(output.getOrElse { "" }.toString())
  .putAllMetadata(metadata.mapValues { it.value.toString() })
  .setExpected(expected.getOrElse { "" }.toString())
  .setActual(actual.getOrElse { "" }.toString())
  .setError(error.getOrElse { "" })
  .setTraceId(traceId.getOrElse { "" })
  .build()

internal fun MockInteraction.toProto(): MockInteractionEvent = MockInteractionEvent.newBuilder()
  .setTestId(testId.orEmpty())
  .setTimestamp(timestamp.toTimestamp())
  .setSystem(system)
  .setProtocol(protocol.name)
  .setMethod(method)
  .setTarget(target)
  .setMatched(matched)
  .setStubId(stubId.orEmpty())
  .setAttribution(attribution.toProto())
  .setRequestBody(requestBody)
  .setRequestBodyTruncated(requestBodyTruncated)
  .setResponseBody(responseBody)
  .setResponseBodyTruncated(responseBodyTruncated)
  .setStatus(status)
  .setLatencyMs(latencyMs ?: -1)
  .addAllNearMisses(nearMisses)
  .setTraceId(traceId.orEmpty())
  .setScenarioName(scenarioName.orEmpty())
  .setScenarioState(scenarioState.orEmpty())
  .setNextScenarioState(nextScenarioState.orEmpty())
  .setConfiguredDelayMs(configuredDelayMs ?: -1)
  .setFault(fault.orEmpty())
  .setClientDeadlineMs(clientDeadlineMs ?: -1)
  .build()

internal fun MockWarning.toProto(): MockWarningEvent = MockWarningEvent.newBuilder()
  .setTestId(testId.orEmpty())
  .setTimestamp(timestamp.toTimestamp())
  .setSystem(system)
  .setKind(kind.name)
  .setMessage(message)
  .setStubId(stubId.orEmpty())
  .setTarget(target.orEmpty())
  .build()

private fun InteractionAttribution.toProto(): MockInteractionAttribution = when (this) {
  InteractionAttribution.PROVEN_HEADER -> MockInteractionAttribution.PROVEN_HEADER
  InteractionAttribution.PROVEN_BAGGAGE -> MockInteractionAttribution.PROVEN_BAGGAGE
  InteractionAttribution.PROVEN_STUB -> MockInteractionAttribution.PROVEN_STUB
  InteractionAttribution.UNATTRIBUTED -> MockInteractionAttribution.UNATTRIBUTED
}

internal fun SpanInfo.toProto(): SpanRecordedEvent = SpanRecordedEvent.newBuilder()
  .setTraceId(traceId)
  .setSpanId(spanId)
  .setParentSpanId(parentSpanId.orEmpty())
  .setOperationName(operationName)
  .setServiceName(serviceName)
  .setStartTimeNanos(startTimeNanos)
  .setEndTimeNanos(endTimeNanos)
  .setStatus(status.name)
  .putAllAttributes(attributes)
  .apply {
    this@toProto.exception?.let { ex ->
      exception = com.trendyol.stove.dashboard.api.ExceptionInfo.newBuilder()
        .setType(ex.type)
        .setMessage(ex.message)
        .addAllStackTrace(ex.stackTrace)
        .build()
    }
  }
  .build()

internal fun Instant.toTimestamp(): Timestamp =
  Timestamp.newBuilder().setSeconds(epochSecond).setNanos(nano).build()
