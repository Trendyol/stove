package com.trendyol.stove.wiremock

import com.github.tomakehurst.wiremock.http.HttpHeaders
import com.github.tomakehurst.wiremock.http.LoggedResponse
import com.github.tomakehurst.wiremock.http.ResponseDefinition
import com.github.tomakehurst.wiremock.matching.RequestPattern
import com.github.tomakehurst.wiremock.stubbing.ServeEvent
import com.github.tomakehurst.wiremock.stubbing.StubMapping
import com.github.tomakehurst.wiremock.verification.LoggedRequest
import com.trendyol.stove.reporting.SystemSnapshot
import com.trendyol.stove.wiremock.WireMockSnapshotDisplayValues as Display
import com.trendyol.stove.wiremock.WireMockSnapshotFieldKeys as Field
import com.trendyol.stove.wiremock.WireMockSnapshotStateKeys as State

internal class WireMockSnapshotBuilder(
  private val reportSystemName: String,
  private val callJournal: WireMockCallJournal,
  activeStubs: List<StubMapping>
) {
  private val activeStubIds = activeStubs.map { it.id }.toSet()

  fun build(testId: String): SystemSnapshot {
    val registeredStubs = callJournal.stubs(testId)
    val activeStubs = registeredStubs.filter { it.id in activeStubIds }
    val serveEvents = callJournal.serveEvents(testId)
    val receivedRequests = serveEvents.map { it.toReceivedRequestSnapshotMap() }
    val unmatchedRequests = serveEvents
      .filterNot { it.wasMatched }
      .map { it.toReceivedRequestSnapshotMap() }

    return SystemSnapshot(
      system = reportSystemName,
      state = mapOf(
        State.REGISTERED_STUBS to registeredStubs.map { it.toSnapshotMap(active = it.id in activeStubIds) },
        State.ACTIVE_STUBS to activeStubs.map { it.toSnapshotMap(active = true) },
        State.RECEIVED_REQUESTS to receivedRequests,
        State.RECORDED_REQUESTS to receivedRequests,
        State.SERVED_REQUESTS to serveEvents.map { it.toServedSnapshotMap() },
        State.UNMATCHED_REQUESTS to unmatchedRequests
      ),
      summary = buildString {
        appendLine(WireMockSnapshotSummary.registeredStubs(registeredStubs.size, activeStubs.size))
        appendLine(WireMockSnapshotSummary.receivedRequests(receivedRequests.size))
        appendLine(WireMockSnapshotSummary.servedRequests(serveEvents.size, serveEvents.count { it.wasMatched }))
        appendLine(WireMockSnapshotSummary.unmatchedRequests(unmatchedRequests.size))
      }
    )
  }
}

internal fun StubMapping.toSnapshotMap(active: Boolean): Map<String, Any> =
  snapshotMap(
    Field.ID to id.toString(),
    Field.NAME to name,
    Field.ACTIVE to active,
    Field.PRIORITY to priority,
    Field.SCENARIO_NAME to scenarioName,
    Field.REQUIRED_SCENARIO_STATE to requiredScenarioState,
    Field.NEW_SCENARIO_STATE to newScenarioState,
    Field.REQUEST to request.toSnapshotMap(),
    Field.RESPONSE to response.toSnapshotMap(),
    Field.METADATA to metadata
      ?.filterKeys { it != WireMockSystem.STOVE_TEST_ID_KEY }
      ?.takeIf { it.isNotEmpty() },
    Field.METHOD to request.method?.value(),
    Field.URL to request.displayUrl(),
    Field.STATUS to response.status
  )

internal fun ServeEvent.toServedSnapshotMap(): Map<String, Any> =
  snapshotMap(
    Field.ID to id.toString(),
    Field.MATCHED to wasMatched,
    Field.STUB_ID to stubMapping?.id?.toString(),
    Field.STUB_NAME to stubMapping?.name,
    Field.REQUEST to request.toSnapshotMap(),
    Field.RESPONSE to response.toSnapshotMap(),
    Field.RESPONSE_DEFINITION to responseDefinition.toSnapshotMap(),
    Field.TIMING to timing?.let {
      snapshotMap(
        Field.ADDED_DELAY_MS to it.addedDelay,
        Field.PROCESS_TIME_MS to it.processTime,
        Field.RESPONSE_SEND_TIME_MS to it.responseSendTime,
        Field.SERVE_TIME_MS to it.serveTime,
        Field.TOTAL_TIME_MS to it.totalTime
      )
    }
  )

internal fun ServeEvent.toReceivedRequestSnapshotMap(): Map<String, Any> =
  request.toSnapshotMap(
    Field.MATCHED to wasMatched,
    Field.STUB_ID to stubMapping?.id?.toString(),
    Field.STUB_NAME to stubMapping?.name
  )

internal fun LoggedRequest.toSnapshotMap(vararg additional: Pair<String, Any?>): Map<String, Any> =
  snapshotMap(
    Field.ID to id?.toString(),
    Field.METHOD to method.value(),
    Field.URL to url,
    Field.ABSOLUTE_URL to absoluteUrl,
    Field.CLIENT_IP to clientIp,
    Field.LOGGED_DATE to loggedDateString,
    Field.HEADERS to headers.toSnapshotMap().takeIf { it.isNotEmpty() },
    Field.QUERY_PARAMS to queryParams.mapValues { it.value.values() }.takeIf { it.isNotEmpty() },
    Field.BODY to bodyAsString
  ) + snapshotMap(*additional)

private fun RequestPattern.toSnapshotMap(): Map<String, Any> =
  snapshotMap(
    Field.METHOD to method?.value(),
    Field.URL to displayUrl(),
    Field.URL_MATCHER to urlMatcher?.toString(),
    Field.HEADERS to headers?.mapValues { it.value.toString() }?.takeIf { it.isNotEmpty() },
    Field.QUERY_PARAMS to queryParameters?.mapValues { it.value.toString() }?.takeIf { it.isNotEmpty() },
    Field.BODY_PATTERNS to bodyPatterns?.map { it.toString() }?.takeIf { it.isNotEmpty() },
    Field.CUSTOM_MATCHER to customMatcher?.toString()
  )

private fun ResponseDefinition.toSnapshotMap(): Map<String, Any> =
  snapshotMap(
    Field.STATUS to status,
    Field.STATUS_MESSAGE to statusMessage,
    Field.HEADERS to headers.toSnapshotMap().takeIf { it.isNotEmpty() },
    Field.BODY to body,
    Field.BODY_FILE_NAME to bodyFileName,
    Field.FAULT to fault?.name,
    Field.FIXED_DELAY_MS to fixedDelayMilliseconds,
    Field.TRANSFORMERS to transformers?.takeIf { it.isNotEmpty() }
  )

private fun LoggedResponse?.toSnapshotMap(): Map<String, Any> =
  this?.let { response ->
    snapshotMap(
      Field.STATUS to response.status,
      Field.HEADERS to response.headers.toSnapshotMap().takeIf { it.isNotEmpty() },
      Field.BODY to response.bodyAsString,
      Field.MIME_TYPE to response.mimeType,
      Field.FAULT to response.fault?.name
    )
  } ?: emptyMap()

private fun HttpHeaders?.toSnapshotMap(): Map<String, List<String>> =
  this?.all()
    ?.associate { header -> header.key() to header.values() }
    ?: emptyMap()

private fun RequestPattern.displayUrl(): String =
  url
    ?: urlPath
    ?: urlPattern
    ?: urlPathPattern
    ?: urlPathTemplate
    ?: urlMatcher?.toString()
    ?: Display.CUSTOM_MATCHER

private fun snapshotMap(vararg entries: Pair<String, Any?>): Map<String, Any> =
  entries.mapNotNull { (key, value) -> value?.let { key to it } }.toMap()
