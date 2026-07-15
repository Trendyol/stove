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

  /**
   * Starts a retry journey: the first [times] requests fail with [withStatus], after which
   * the behaviour continues with [thenSucceeds] (or any [then] step).
   *
   * ```kotlin
   * behaviourFor("/payments", ::post) {
   *   failsTimes(2, withStatus = 503)
   *   thenSucceeds { aResponse().withStatus(200).withBody("recovered") }
   * }
   * ```
   */
  fun failsTimes(times: Int, withStatus: Int = SERVICE_UNAVAILABLE) {
    check(initializedCounter == 0) { WireMockBehaviourMessages.FAILS_TIMES_FIRST }
    require(times >= 1) { WireMockBehaviourMessages.FAILS_TIMES_POSITIVE }
    repeat(times) {
      stateCounter++
      val nextState = WireMockBehaviourNames.state(stateCounter)
      createStub(WireMock.aResponse().withStatus(withStatus), previousState, nextState)
      previousState = nextState
    }
    initializedCounter++
  }

  /** The step after [failsTimes]: what the dependency returns once it has recovered. */
  fun thenSucceeds(step: () -> ResponseDefinitionBuilder): Unit = then(step)

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

  companion object {
    private const val SERVICE_UNAVAILABLE = 503
  }
}
