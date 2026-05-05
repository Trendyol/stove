package com.trendyol.stove.wiremock

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.*
import com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED
import com.github.tomakehurst.wiremock.stubbing.StubMapping
import com.trendyol.stove.serialization.StoveSerde

internal fun stubBehaviour(
  wireMockServer: WireMockServer,
  serde: StoveSerde<Any, ByteArray>,
  url: String,
  method: (String) -> MappingBuilder,
  metadata: Map<String, Any> = emptyMap(),
  recordStub: (StubMapping) -> Unit = {},
  block: StubBehaviourBuilder.(StoveSerde<Any, ByteArray>) -> Unit
) {
  val builder = StubBehaviourBuilder(wireMockServer, url, method, metadata, recordStub)
  builder.block(serde)
}

class StubBehaviourBuilder(
  private val wireMockServer: WireMockServer,
  private val url: String,
  private val method: (String) -> MappingBuilder,
  private val metadata: Map<String, Any> = emptyMap()
) {
  private val scenarioName = WireMockBehaviourNames.scenarioName(url)
  private var previousState: String = STARTED
  private var stateCounter = 0
  private var initializedCounter = 0
  private var recordStub: (StubMapping) -> Unit = {}

  internal constructor(
    wireMockServer: WireMockServer,
    url: String,
    method: (String) -> MappingBuilder,
    metadata: Map<String, Any> = emptyMap(),
    recordStub: (StubMapping) -> Unit
  ) : this(wireMockServer, url, method, metadata) {
    this.recordStub = recordStub
  }

  fun initially(step: () -> ResponseDefinitionBuilder) {
    check(initializedCounter == 0) { WireMockBehaviourMessages.INITIALLY_ONCE }
    stateCounter++
    val nextState = WireMockBehaviourNames.state(stateCounter)
    createStub(step(), previousState, nextState)
    previousState = nextState
    initializedCounter++
  }

  fun then(step: () -> ResponseDefinitionBuilder) {
    check(previousState != STARTED) { WireMockBehaviourMessages.INITIALLY_BEFORE_THEN }
    stateCounter++
    val nextState = WireMockBehaviourNames.state(stateCounter)
    createStub(step(), previousState, nextState)
    previousState = nextState
  }

  private fun createStub(
    response: ResponseDefinitionBuilder,
    whenState: String,
    setState: String
  ) {
    val stub = wireMockServer.stubFor(
      method(url)
        .inScenario(scenarioName)
        .whenScenarioStateIs(whenState)
        .willReturn(response)
        .willSetStateTo(setState)
        .withMetadata(metadata)
    )
    recordStub(stub)
  }
}
