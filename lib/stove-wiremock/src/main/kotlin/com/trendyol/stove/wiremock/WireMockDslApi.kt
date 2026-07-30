@file:OptIn(com.trendyol.stove.ExperimentalStoveApi::class)

package com.trendyol.stove.wiremock

import com.github.tomakehurst.wiremock.client.MappingBuilder
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder
import com.github.tomakehurst.wiremock.client.WireMock
import com.github.tomakehurst.wiremock.client.WireMock.equalTo
import com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo
import com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching
import com.github.tomakehurst.wiremock.http.RequestMethod
import com.github.tomakehurst.wiremock.matching.RequestPatternBuilder
import com.github.tomakehurst.wiremock.matching.StringValuePattern
import com.github.tomakehurst.wiremock.matching.UrlPattern
import com.github.tomakehurst.wiremock.stubbing.Scenario
import com.github.tomakehurst.wiremock.stubbing.StubMapping
import com.trendyol.stove.ExperimentalStoveApi
import com.trendyol.stove.serialization.StoveSerde
import java.util.UUID
import kotlin.time.Duration

private const val MIN_HTTP_STATUS = 100
private const val MAX_HTTP_STATUS = 599

/**
 * A reusable, immutable request description shared by stubbing, verification, and call lookup.
 */
@ExperimentalStoveApi
class RequestSpec internal constructor(
  internal val model: RequestModel
)

/**
 * An explicit WireMock URL matching strategy.
 */
@ExperimentalStoveApi
class RequestTarget internal constructor(
  internal val kind: RequestTargetKind,
  internal val value: String
)

@ExperimentalStoveApi
fun exactUrl(value: String): RequestTarget = RequestTarget(RequestTargetKind.EXACT_URL, value)

@ExperimentalStoveApi
fun path(value: String): RequestTarget = RequestTarget(RequestTargetKind.PATH, value)

@ExperimentalStoveApi
fun pathRegex(value: String): RequestTarget = RequestTarget(RequestTargetKind.PATH_REGEX, value)

@WiremockDsl
@ExperimentalStoveApi
class StubDsl internal constructor(
  private var requestModel: RequestModel
) {
  private var requestConfigured = false
  private var responseConfigured = false
  private var responseModel = ResponseModel()
  private var behaviourModel: BehaviourModel? = null

  fun request(configure: RequestDsl.() -> Unit) {
    check(!requestConfigured) { "A request block has already been configured" }
    requestModel = RequestDsl(requestModel).apply(configure).build()
    requestConfigured = true
  }

  fun respond(configure: ResponseDsl.() -> Unit) {
    check(!responseConfigured) { "A response block has already been configured" }
    check(behaviourModel == null) { "A behaviour block has already been configured" }
    responseModel = ResponseDsl(responseModel).apply(configure).build()
    responseConfigured = true
  }

  fun behaviour(configure: BehaviourDsl.() -> Unit) {
    check(!responseConfigured) { "A response block has already been configured" }
    check(behaviourModel == null) { "A behaviour block has already been configured" }
    behaviourModel = BehaviourDsl().apply(configure).build()
  }

  internal fun build(): DslStubDefinition =
    DslStubDefinition(
      request = requestModel,
      responsePlan = behaviourModel
        ?.let(StubResponsePlan::Behaviour)
        ?: StubResponsePlan.Single(responseModel)
    )
}

@WiremockDsl
@ExperimentalStoveApi
class RequestDsl internal constructor(
  initial: RequestModel
) {
  private val method = initial.method
  private val target = initial.target
  private val headers = initial.headers.toMutableList()
  private val queries = initial.queries.toMutableList()
  private val bodies = initial.bodies.toMutableList()

  fun header(name: String, value: String) {
    header(name, equalTo(value))
  }

  fun header(name: String, pattern: StringValuePattern) {
    requireName("Header", name)
    headers += NamedPattern(name, pattern)
  }

  fun query(name: String, value: String) {
    query(name, equalTo(value))
  }

  fun query(name: String, pattern: StringValuePattern) {
    requireName("Query parameter", name)
    queries += NamedPattern(name, pattern)
  }

  fun contentTypeJson() {
    header(
      WireMockHeaders.CONTENT_TYPE,
      WireMock.containing(WireMockHeaders.APPLICATION_JSON)
    )
  }

  fun jsonEqualTo(
    value: Any,
    ignoreArrayOrder: Boolean = true,
    ignoreExtraElements: Boolean = false
  ) {
    bodies += RequestBodyConstraint.JsonEquality(value, ignoreArrayOrder, ignoreExtraElements)
  }

  fun jsonField(path: String, value: Any) {
    require(path.isNotBlank()) { "JSON field path must not be blank" }
    bodies += RequestBodyConstraint.JsonField(path, value)
  }

  fun jsonPath(expression: String, value: String) {
    jsonPath(expression, equalTo(value))
  }

  fun jsonPath(expression: String, pattern: StringValuePattern) {
    require(expression.isNotBlank()) { "JSONPath expression must not be blank" }
    bodies += RequestBodyConstraint.JsonPath(expression, pattern)
  }

  internal fun build(): RequestModel =
    RequestModel(
      method = method,
      target = target,
      headers = headers.toList(),
      queries = queries.toList(),
      bodies = bodies.toList()
    )

  private fun requireName(kind: String, name: String) {
    require(name.isNotBlank()) { "$kind name must not be blank" }
  }
}

@WiremockDsl
@ExperimentalStoveApi
class ResponseDsl internal constructor(
  initial: ResponseModel
) {
  var status: Int = initial.status
  var delay: Duration? = initial.delay
  private val headers = initial.headers.toMutableList()
  private var body = initial.body

  fun header(name: String, value: String) {
    require(name.isNotBlank()) { "Response header name must not be blank" }
    headers += ResponseHeader(name, value)
  }

  fun json(value: Any) {
    setBody(ResponseBody.Json(value))
  }

  fun jsonNull() {
    setBody(ResponseBody.JsonNull)
  }

  fun rawJson(value: String) {
    setBody(ResponseBody.RawJson(value))
  }

  fun text(value: String) {
    setBody(ResponseBody.Text(value))
  }

  fun bytes(value: ByteArray, contentType: String) {
    require(contentType.isNotBlank()) { "Binary response content type must not be blank" }
    setBody(ResponseBody.Bytes(value.copyOf(), contentType))
  }

  fun empty() {
    setBody(ResponseBody.Empty)
  }

  internal fun build(): ResponseModel {
    require(status in MIN_HTTP_STATUS..MAX_HTTP_STATUS) {
      "WireMock response status must be between $MIN_HTTP_STATUS and $MAX_HTTP_STATUS"
    }
    delay?.toWireMockDelayMilliseconds()
    return ResponseModel(
      status = status,
      headers = headers.toList(),
      body = body,
      delay = delay
    )
  }

  private fun setBody(value: ResponseBody) {
    check(body == null) { "A response body has already been configured" }
    body = value
  }
}

/**
 * Defines ordered transient responses followed by one response that remains active.
 */
@WiremockDsl
@ExperimentalStoveApi
class BehaviourDsl internal constructor() {
  private val responses = mutableListOf<ResponseModel>()
  private var terminalResponse: ResponseModel? = null

  /**
   * Adds one transient response to the behaviour.
   */
  fun respond(configure: ResponseDsl.() -> Unit) {
    checkNotCompleted()
    responses += ResponseDsl(ResponseModel()).apply(configure).build()
  }

  /**
   * Adds one delayed response. It produces a client timeout when [after] exceeds
   * the client's request timeout.
   */
  fun timeout(after: Duration) {
    checkNotCompleted()
    require(after.isFinite() && after.isPositive()) {
      "Behaviour timeout must be finite and positive"
    }
    require(after.inWholeMilliseconds >= 1) {
      "Behaviour timeout must be at least 1 millisecond"
    }
    after.toWireMockDelayMilliseconds()
    responses += ResponseModel(delay = after)
  }

  /**
   * Defines the terminal response, which remains active for all subsequent matching calls.
   */
  fun thenAlways(configure: ResponseDsl.() -> Unit) {
    check(terminalResponse == null) { "thenAlways has already been configured" }
    terminalResponse = ResponseDsl(ResponseModel()).apply(configure).build()
  }

  internal fun build(): BehaviourModel {
    require(responses.isNotEmpty()) {
      "A behaviour must contain at least one response before thenAlways"
    }
    return BehaviourModel(
      responses = responses.toList(),
      terminalResponse = checkNotNull(terminalResponse) {
        "A behaviour must end with thenAlways"
      }
    )
  }

  private fun checkNotCompleted() {
    check(terminalResponse == null) {
      "No responses can be added after thenAlways"
    }
  }
}

@WiremockDsl
@ExperimentalStoveApi
class RawStubDsl internal constructor(
  val serde: StoveSerde<Any, ByteArray>
)

internal enum class RequestTargetKind {
  EXACT_URL,
  PATH,
  PATH_REGEX
}

internal data class DslStubDefinition(
  val request: RequestModel,
  val responsePlan: StubResponsePlan
)

internal sealed interface StubResponsePlan {
  data class Single(val response: ResponseModel) : StubResponsePlan

  data class Behaviour(val model: BehaviourModel) : StubResponsePlan
}

internal data class BehaviourModel(
  val responses: List<ResponseModel>,
  val terminalResponse: ResponseModel
)

internal data class RequestModel(
  val method: RequestMethod,
  val target: RequestTarget,
  val headers: List<NamedPattern> = emptyList(),
  val queries: List<NamedPattern> = emptyList(),
  val bodies: List<RequestBodyConstraint> = emptyList()
)

internal data class NamedPattern(
  val name: String,
  val pattern: StringValuePattern
)

internal sealed interface RequestBodyConstraint {
  data class JsonEquality(
    val value: Any,
    val ignoreArrayOrder: Boolean,
    val ignoreExtraElements: Boolean
  ) : RequestBodyConstraint

  data class JsonField(
    val path: String,
    val value: Any
  ) : RequestBodyConstraint

  data class JsonPath(
    val expression: String,
    val pattern: StringValuePattern
  ) : RequestBodyConstraint
}

internal data class ResponseModel(
  val status: Int = 200,
  val headers: List<ResponseHeader> = emptyList(),
  val body: ResponseBody? = null,
  val delay: Duration? = null
)

internal data class ResponseHeader(
  val name: String,
  val value: String
)

internal sealed interface ResponseBody {
  data class Json(val value: Any) : ResponseBody

  data object JsonNull : ResponseBody

  data class RawJson(val value: String) : ResponseBody

  data class Text(val value: String) : ResponseBody

  data class Bytes(val value: ByteArray, val contentType: String) : ResponseBody {
    override fun equals(other: Any?): Boolean =
      this === other ||
        (
          other is Bytes &&
            value.contentEquals(other.value) &&
            contentType == other.contentType
          )

    override fun hashCode(): Int = 31 * value.contentHashCode() + contentType.hashCode()
  }

  data object Empty : ResponseBody
}

internal class WireMockDslCompiler(
  private val serde: StoveSerde<Any, ByteArray>
) {
  fun mappings(definition: DslStubDefinition): List<StubMapping> =
    when (val plan = definition.responsePlan) {
      is StubResponsePlan.Single ->
        listOf(
          mapping(definition.request)
            .willReturn(response(plan.response))
            .build()
        )

      is StubResponsePlan.Behaviour ->
        behaviourMappings(definition.request, plan.model)
    }

  fun mapping(model: RequestModel): MappingBuilder =
    WireMock.request(model.method.value(), model.target.toUrlPattern())
      .also { it.applyRequestConstraints(model, serde) }

  fun pattern(model: RequestModel): RequestPatternBuilder =
    RequestPatternBuilder
      .newRequestPattern(model.method, model.target.toUrlPattern())
      .also { it.applyRequestConstraints(model, serde) }

  private fun behaviourMappings(
    request: RequestModel,
    behaviour: BehaviourModel
  ): List<StubMapping> {
    val scenarioName = "Stove behaviour ${UUID.randomUUID()}"
    var currentState = Scenario.STARTED

    val transientMappings = behaviour.responses.mapIndexed { index, model ->
      val nextState = WireMockBehaviourNames.state(index + 1)
      val mapping = mapping(request)
        .inScenario(scenarioName)
        .whenScenarioStateIs(currentState)
        .willSetStateTo(nextState)
        .willReturn(response(model))
        .build()
      currentState = nextState
      mapping
    }

    val terminalMapping = mapping(request)
      .inScenario(scenarioName)
      .whenScenarioStateIs(currentState)
      .withMetadata(mapOf(WireMockSystem.STOVE_PERSISTENT_STUB_KEY to true))
      .willReturn(response(behaviour.terminalResponse))
      .build()

    return transientMappings + terminalMapping
  }

  private fun response(model: ResponseModel): ResponseDefinitionBuilder {
    val response = WireMock.aResponse().withStatus(model.status)
    val explicitContentType = model.headers.any {
      it.name.equals(WireMockHeaders.CONTENT_TYPE, ignoreCase = true)
    }

    model.body?.let { body ->
      when (body) {
        is ResponseBody.Json -> response.withBody(serde.serialize(body.value))
        ResponseBody.JsonNull -> response.withBody("null")
        is ResponseBody.RawJson -> response.withBody(body.value)
        is ResponseBody.Text -> response.withBody(body.value)
        is ResponseBody.Bytes -> response.withBody(body.value)
        ResponseBody.Empty -> Unit
      }

      if (!explicitContentType) {
        body.defaultContentType()?.let {
          response.withHeader(WireMockHeaders.CONTENT_TYPE, it)
        }
      }
    }

    model.headers.forEach { response.withHeader(it.name, it.value) }
    model.delay?.let { response.withFixedDelay(it.toWireMockDelayMilliseconds()) }
    return response
  }
}

private fun MappingBuilder.applyRequestConstraints(
  model: RequestModel,
  serde: StoveSerde<Any, ByteArray>
) {
  model.headers.forEach { withHeader(it.name, it.pattern) }
  model.queries.forEach { withQueryParam(it.name, it.pattern) }
  model.bodies.forEach { withRequestBody(it.toWireMockPattern(serde)) }
}

private fun RequestPatternBuilder.applyRequestConstraints(
  model: RequestModel,
  serde: StoveSerde<Any, ByteArray>
) {
  model.headers.forEach { withHeader(it.name, it.pattern) }
  model.queries.forEach { withQueryParam(it.name, it.pattern) }
  model.bodies.forEach { withRequestBody(it.toWireMockPattern(serde)) }
}

private fun RequestBodyConstraint.toWireMockPattern(
  serde: StoveSerde<Any, ByteArray>
) = when (this) {
  is RequestBodyConstraint.JsonEquality ->
    createWireMockJsonEqualityMatcher(
      value,
      serde,
      ignoreArrayOrder,
      ignoreExtraElements
    )

  is RequestBodyConstraint.JsonField ->
    matchingJsonPath(
      WireMockJsonPath.field(path),
      createWireMockValueMatcher(value, serde)
    )

  is RequestBodyConstraint.JsonPath -> matchingJsonPath(expression, pattern)
}

private fun RequestTarget.toUrlPattern(): UrlPattern = when (kind) {
  RequestTargetKind.EXACT_URL -> urlEqualTo(value)
  RequestTargetKind.PATH -> urlPathEqualTo(value)
  RequestTargetKind.PATH_REGEX -> urlPathMatching(value)
}

private fun ResponseBody.defaultContentType(): String? = when (this) {
  is ResponseBody.Json,
  ResponseBody.JsonNull,
  is ResponseBody.RawJson -> WireMockHeaders.APPLICATION_JSON

  is ResponseBody.Text -> TEXT_PLAIN

  is ResponseBody.Bytes -> contentType

  ResponseBody.Empty -> null
}

private const val TEXT_PLAIN = "text/plain"
