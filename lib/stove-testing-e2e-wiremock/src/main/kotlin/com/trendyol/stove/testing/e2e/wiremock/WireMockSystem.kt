@file:Suppress("unused")

package com.trendyol.stove.testing.e2e.wiremock

import arrow.core.*
import com.github.benmanes.caffeine.cache.*
import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.*
import com.github.tomakehurst.wiremock.client.WireMock.*
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig
import com.github.tomakehurst.wiremock.extension.Extension
import com.github.tomakehurst.wiremock.matching.*
import com.github.tomakehurst.wiremock.stubbing.*
import com.trendyol.stove.functional.*
import com.trendyol.stove.testing.e2e.serialization.StoveSerde
import com.trendyol.stove.testing.e2e.system.TestSystem
import com.trendyol.stove.testing.e2e.system.abstractions.*
import kotlinx.coroutines.runBlocking
import wiremock.org.slf4j.*
import java.util.*

/**
 * Callback invoked after a stub is removed (when `removeStubAfterRequestMatched` is enabled).
 */
typealias AfterStubRemoved = (ServeEvent, Cache<UUID, StubMapping>) -> Unit

/**
 * Callback invoked after a request is handled by WireMock.
 */
typealias AfterRequestHandler = (ServeEvent, Cache<UUID, StubMapping>) -> Unit

/**
 * WireMock HTTP mocking system for testing external service integrations.
 *
 * WireMock allows you to mock external HTTP services that your application depends on,
 * enabling isolated testing without actual network calls.
 *
 * ## Mocking GET Requests
 *
 * ```kotlin
 * wiremock {
 *     // Simple GET mock
 *     mockGet(
 *         url = "/api/users/123",
 *         statusCode = 200,
 *         responseBody = User(id = "123", name = "John").some()
 *     )
 *
 *     // GET with custom headers
 *     mockGet(
 *         url = "/api/users/123",
 *         statusCode = 200,
 *         responseBody = user.some(),
 *         responseHeaders = mapOf(
 *             "Content-Type" to "application/json",
 *             "X-Custom-Header" to "value"
 *         )
 *     )
 * }
 * ```
 *
 * ## Mocking POST Requests
 *
 * ```kotlin
 * wiremock {
 *     // POST with request and response bodies
 *     mockPost(
 *         url = "/api/payments",
 *         statusCode = 200,
 *         requestBody = PaymentRequest(amount = 99.99).some(),
 *         responseBody = PaymentResponse(transactionId = "txn-123").some()
 *     )
 *
 *     // POST returning error
 *     mockPost(
 *         url = "/api/payments",
 *         statusCode = 400,
 *         responseBody = ErrorResponse(code = "INVALID_AMOUNT").some()
 *     )
 * }
 * ```
 *
 * ## Mocking PUT, DELETE, PATCH
 *
 * ```kotlin
 * wiremock {
 *     mockPut(
 *         url = "/api/users/123",
 *         statusCode = 200,
 *         requestBody = UpdateUserRequest(name = "Jane").some(),
 *         responseBody = User(id = "123", name = "Jane").some()
 *     )
 *
 *     mockDelete(
 *         url = "/api/users/123",
 *         statusCode = 204
 *     )
 *
 *     mockPatch(
 *         url = "/api/users/123",
 *         statusCode = 200,
 *         requestBody = mapOf("status" to "active").some(),
 *         responseBody = User(id = "123", status = "active").some()
 *     )
 * }
 * ```
 *
 * ## Verifying Requests
 *
 * ```kotlin
 * wiremock {
 *     // Verify a request was made
 *     verify { getRequestedFor(urlEqualTo("/api/users/123")) }
 *
 *     // Verify request count
 *     verify(2) { postRequestedFor(urlEqualTo("/api/payments")) }
 *
 *     // Verify with request body
 *     verify {
 *         postRequestedFor(urlEqualTo("/api/users"))
 *             .withRequestBody(matchingJsonPath("$.name", equalTo("John")))
 *     }
 * }
 * ```
 *
 * ## Test Workflow Example
 *
 * ```kotlin
 * test("should process payment via external gateway") {
 *     TestSystem.validate {
 *         // Mock external payment gateway
 *         wiremock {
 *             mockPost(
 *                 url = "/gateway/charge",
 *                 statusCode = 200,
 *                 responseBody = GatewayResponse(success = true, txnId = "123").some()
 *             )
 *         }
 *
 *         // Make request to our application (which calls the gateway)
 *         http {
 *             postAndExpectJson<OrderResponse>(
 *                 uri = "/orders",
 *                 body = CreateOrderRequest(amount = 99.99).some()
 *             ) { order ->
 *                 order.status shouldBe "PAID"
 *                 order.transactionId shouldBe "123"
 *             }
 *         }
 *
 *         // Verify the gateway was called
 *         wiremock {
 *             verify { postRequestedFor(urlEqualTo("/gateway/charge")) }
 *         }
 *     }
 * }
 * ```
 *
 * ## Configuration
 *
 * ```kotlin
 * TestSystem()
 *     .with {
 *         wiremock {
 *             WireMockSystemOptions(
 *                 port = 9090,
 *                 removeStubAfterRequestMatched = true,  // Clean stubs after use
 *                 afterRequest = { event, _ ->
 *                     println("Request: ${event.request}")
 *                 }
 *             )
 *         }
 *     }
 * ```
 *
 * @property testSystem The parent test system.
 * @see WireMockSystemOptions
 */
@WiremockDsl
class WireMockSystem(
  override val testSystem: TestSystem,
  ctx: WireMockContext
) : PluggedSystem,
  ValidatedSystem,
  RunAware {
  private val stubLog: Cache<UUID, StubMapping> = Caffeine.newBuilder().build()
  private var wireMock: WireMockServer
  private val serde: StoveSerde<Any, ByteArray> = ctx.serde
  private val logger: Logger = LoggerFactory.getLogger(javaClass)

  init {
    val cfg = wireMockConfig().port(ctx.port).extensions(WireMockRequestListener(stubLog, ctx.afterRequest))
    val stoveExtensions = mutableListOf<Extension>()
    if (ctx.removeStubAfterRequestMatched) {
      stoveExtensions.add(WireMockVacuumCleaner(stubLog, ctx.afterStubRemoved))
    }
    stoveExtensions.map { cfg.extensions(it) }
    wireMock = WireMockServer(cfg.let(ctx.configure))
    stoveExtensions.filterIsInstance<WireMockVacuumCleaner>().forEach { it.wireMock(wireMock) }
  }

  override suspend fun run(): Unit = wireMock.start()

  override suspend fun stop(): Unit = wireMock.shutdownServer()

  @WiremockDsl
  fun mockGet(
    url: String,
    statusCode: Int,
    responseBody: Option<Any> = None,
    metadata: Map<String, String> = mapOf(),
    responseHeaders: Map<String, String> = mapOf()
  ): WireMockSystem {
    val mockRequest = get(urlEqualTo(url))
    mockRequest.withMetadata(metadata)
    val mockResponse = configureResponse(statusCode, responseBody, responseHeaders)
    val stub = wireMock.stubFor(mockRequest.willReturn(mockResponse).withId(UUID.randomUUID()))
    stubLog.put(stub.id, stub)
    return this
  }

  @WiremockDsl
  fun mockPost(
    url: String,
    statusCode: Int,
    requestBody: Option<Any> = None,
    responseBody: Option<Any> = None,
    metadata: Map<String, Any> = mapOf(),
    responseHeaders: Map<String, String> = mapOf()
  ): WireMockSystem {
    val mockRequest = post(urlEqualTo(url))
    configureBodyAndMetadata(mockRequest, metadata, requestBody)
    val mockResponse = configureResponse(statusCode, responseBody, responseHeaders)
    val stub = wireMock.stubFor(mockRequest.willReturn(mockResponse).withId(UUID.randomUUID()))
    stubLog.put(stub.id, stub)
    return this
  }

  @WiremockDsl
  fun mockPut(
    url: String,
    statusCode: Int,
    requestBody: Option<Any> = None,
    responseBody: Option<Any> = None,
    metadata: Map<String, Any> = mapOf(),
    responseHeaders: Map<String, String> = mapOf()
  ): WireMockSystem {
    val req = put(urlEqualTo(url))
    configureBodyAndMetadata(req, metadata, requestBody)
    val mockResponse = configureResponse(statusCode, responseBody, responseHeaders)
    val stub = wireMock.stubFor(req.willReturn(mockResponse).withId(UUID.randomUUID()))
    stubLog.put(stub.id, stub)
    return this
  }

  @WiremockDsl
  fun mockPatch(
    url: String,
    statusCode: Int,
    requestBody: Option<Any> = None,
    responseBody: Option<Any> = None,
    metadata: Map<String, Any> = mapOf(),
    responseHeaders: Map<String, String> = mapOf()
  ): WireMockSystem {
    val req = patch(urlEqualTo(url))
    configureBodyAndMetadata(req, metadata, requestBody)
    val mockResponse = configureResponse(statusCode, responseBody, responseHeaders)
    val stub = wireMock.stubFor(req.willReturn(mockResponse).withId(UUID.randomUUID()))
    stubLog.put(stub.id, stub)
    return this
  }

  @WiremockDsl
  fun mockDelete(
    url: String,
    statusCode: Int,
    metadata: Map<String, Any> = mapOf()
  ): WireMockSystem {
    val mockRequest = delete(urlEqualTo(url))
    configureBodyAndMetadata(mockRequest, metadata, None)

    val mockResponse = configureResponse(statusCode, None, mapOf())
    val stub = wireMock.stubFor(mockRequest.willReturn(mockResponse).withId(UUID.randomUUID()))
    stubLog.put(stub.id, stub)
    return this
  }

  @WiremockDsl
  fun mockHead(
    url: String,
    statusCode: Int,
    metadata: Map<String, Any> = mapOf()
  ): WireMockSystem {
    val mockRequest = head(urlEqualTo(url))
    configureBodyAndMetadata(mockRequest, metadata, None)

    val mockResponse = configureResponse(statusCode, None, mapOf())
    val stub = wireMock.stubFor(mockRequest.willReturn(mockResponse).withId(UUID.randomUUID()))
    stubLog.put(stub.id, stub)
    return this
  }

  @WiremockDsl
  fun mockPutConfigure(
    url: String,
    urlPatternFn: (url: String) -> UrlPattern = { urlEqualTo(it) },
    configure: (MappingBuilder, StoveSerde<Any, ByteArray>) -> MappingBuilder
  ): WireMockSystem {
    val req = put(urlPatternFn(url))
    val stub = wireMock.stubFor(configure(req, serde).withId(UUID.randomUUID()))
    stubLog.put(stub.id, stub)
    return this
  }

  @WiremockDsl
  fun mockPatchConfigure(
    url: String,
    urlPatternFn: (url: String) -> UrlPattern = { urlEqualTo(it) },
    configure: (MappingBuilder, StoveSerde<Any, ByteArray>) -> MappingBuilder
  ): WireMockSystem {
    val req = patch(urlPatternFn(url))
    val stub = wireMock.stubFor(configure(req, serde).withId(UUID.randomUUID()))
    stubLog.put(stub.id, stub)
    return this
  }

  @WiremockDsl
  fun mockGetConfigure(
    url: String,
    urlPatternFn: (url: String) -> UrlPattern = { urlEqualTo(it) },
    configure: (MappingBuilder, StoveSerde<Any, ByteArray>) -> MappingBuilder
  ): WireMockSystem {
    val req = get(urlPatternFn(url))
    val stub = wireMock.stubFor(configure(req, serde).withId(UUID.randomUUID()))
    stubLog.put(stub.id, stub)
    return this
  }

  @WiremockDsl
  fun mockHeadConfigure(
    url: String,
    urlPatternFn: (url: String) -> UrlPattern = { urlEqualTo(it) },
    configure: (MappingBuilder, StoveSerde<Any, ByteArray>) -> MappingBuilder
  ): WireMockSystem {
    val req = head(urlPatternFn(url))
    val stub = wireMock.stubFor(configure(req, serde).withId(UUID.randomUUID()))
    stubLog.put(stub.id, stub)
    return this
  }

  @WiremockDsl
  fun mockDeleteConfigure(
    url: String,
    urlPatternFn: (url: String) -> UrlPattern = { urlEqualTo(it) },
    configure: (MappingBuilder, StoveSerde<Any, ByteArray>) -> MappingBuilder
  ): WireMockSystem {
    val req = delete(urlPatternFn(url))
    val stub = wireMock.stubFor(configure(req, serde).withId(UUID.randomUUID()))
    stubLog.put(stub.id, stub)
    return this
  }

  @WiremockDsl
  fun mockPostConfigure(
    url: String,
    urlPatternFn: (url: String) -> UrlPattern = { urlEqualTo(it) },
    configure: (MappingBuilder, StoveSerde<Any, ByteArray>) -> MappingBuilder
  ): WireMockSystem {
    val req = post(urlPatternFn(url))
    val stub = wireMock.stubFor(configure(req, serde).withId(UUID.randomUUID()))
    stubLog.put(stub.id, stub)
    return this
  }

  @WiremockDsl
  fun behaviourFor(
    url: String,
    method: (String) -> MappingBuilder,
    block: StubBehaviourBuilder.(StoveSerde<Any, ByteArray>) -> Unit
  ) {
    stubBehaviour(wireMock, serde = serde, url, method, block)
  }

  @WiremockDsl
  override suspend fun validate() {
    data class ValidationResult(
      val url: String,
      val bodyAsString: String,
      val queryParams: String
    ) {
      override fun toString(): String =
        """
                Url: $url
                Body: $bodyAsString
                QueryParams: $queryParams
        """.trimIndent()
    }
    if (wireMock.findAllUnmatchedRequests().any()) {
      val problems = wireMock.findAllUnmatchedRequests().joinToString("\n") {
        ValidationResult(
          "${it.method.value()} ${it.url}",
          it.bodyAsString,
          serde.serialize(it.queryParams).decodeToString()
        ).toString()
      }
      throw AssertionError(
        "There are unmatched requests in the mock pipeline, please satisfy all the wanted requests.\n$problems"
      )
    }
  }

  override fun close(): Unit = runBlocking {
    Try {
      stop()
    }.recover { logger.warn("got an error while stopping wiremock: ${it.message}") }
  }

  private fun configureBodyAndMetadata(
    request: MappingBuilder,
    metadata: Map<String, Any>,
    body: Option<Any>
  ) {
    request.withMetadata(metadata)
    body.map {
      request
        .withRequestBody(
          equalToJson(
            serde.serialize(it).decodeToString(),
            true,
            false
          )
        ).withHeader("Content-Type", ContainsPattern("application/json"))
    }
  }

  private fun configureResponse(
    statusCode: Int,
    responseBody: Option<Any>,
    responseHeaders: Map<String, String>
  ): ResponseDefinitionBuilder? {
    val mockResponse = aResponse()
      .withStatus(statusCode)
      .withHeader("Content-Type", "application/json; charset=UTF-8")
    responseHeaders.forEach {
      mockResponse.withHeader(it.key, it.value)
    }
    responseBody.map { mockResponse.withBody(serde.serialize(it)) }
    return mockResponse
  }

  companion object {
    /**
     * Exposes the [WireMockServer] instance for the given [WireMockSystem].
     */
    @Suppress("unused")
    @WiremockDsl
    fun WireMockSystem.server(): WireMockServer = wireMock
  }
}
