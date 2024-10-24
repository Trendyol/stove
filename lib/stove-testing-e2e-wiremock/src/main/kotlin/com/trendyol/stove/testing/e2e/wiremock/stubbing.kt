package com.trendyol.stove.testing.e2e.wiremock

import com.fasterxml.jackson.databind.ObjectMapper
import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.*
import com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED

internal fun stubBehaviour(
  wireMockServer: WireMockServer,
  objectMapper: ObjectMapper,
  url: String,
  method: (String) -> MappingBuilder,
  block: StubBehaviourBuilder.(ObjectMapper) -> Unit
) {
  val builder = StubBehaviourBuilder(wireMockServer, url, method)
  builder.block(objectMapper)
}

class StubBehaviourBuilder(
  private val wireMockServer: WireMockServer,
  private val url: String,
  private val method: (String) -> MappingBuilder
) {
  private val scenarioName = "Scenario for $url"
  private var previousState: String = STARTED
  private var stateCounter = 0

  fun initially(step: () -> ResponseDefinitionBuilder) {
    stateCounter++
    val nextState = "State$stateCounter"
    createStub(step(), previousState, nextState)
    previousState = nextState
  }

  fun then(step: () -> ResponseDefinitionBuilder) {
    stateCounter++
    val nextState = "State$stateCounter"
    createStub(step(), previousState, nextState)
    previousState = nextState
  }

  private fun createStub(
    response: ResponseDefinitionBuilder,
    whenState: String,
    setState: String
  ) {
    wireMockServer.stubFor(
      method(url)
        .inScenario(scenarioName)
        .whenScenarioStateIs(whenState)
        .willReturn(response)
        .willSetStateTo(setState)
    )
  }
}
