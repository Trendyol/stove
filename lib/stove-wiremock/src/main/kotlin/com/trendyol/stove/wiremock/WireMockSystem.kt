@file:Suppress("unused")

package com.trendyol.stove.wiremock

import arrow.core.*
import com.github.benmanes.caffeine.cache.*
import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.*
import com.github.tomakehurst.wiremock.client.WireMock.*
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig
import com.github.tomakehurst.wiremock.extension.Extension
import com.github.tomakehurst.wiremock.http.RequestMethod
import com.github.tomakehurst.wiremock.matching.*
import com.github.tomakehurst.wiremock.stubbing.*
import com.github.tomakehurst.wiremock.verification.LoggedRequest
import com.trendyol.stove.functional.*
import com.trendyol.stove.reporting.*
import com.trendyol.stove.scoping.TestScopeCleanupListener
import com.trendyol.stove.serialization.StoveSerde
import com.trendyol.stove.system.Stove
import com.trendyol.stove.system.abstractions.*
import com.trendyol.stove.wiremock.WireMockHeaders.APPLICATION_JSON
import com.trendyol.stove.wiremock.WireMockHeaders.APPLICATION_JSON_UTF8
import com.trendyol.stove.wiremock.WireMockHeaders.CONTENT_TYPE
import com.trendyol.stove.wiremock.WireMockReportActions.VALIDATE_ALL_REQUESTS_MATCHED
import com.trendyol.stove.wiremock.WireMockReportActions.VALIDATE_ALL_REQUESTS_SHOULD_MATCH
import com.trendyol.stove.wiremock.WireMockReportMetadataKeys.RESPONSE_HEADERS
import com.trendyol.stove.wiremock.WireMockReportMetadataKeys.STATUS_CODE
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
 *     // Verify a request was made exactly once
 *     shouldHaveBeenCalled(RequestMethod.GET, "/api/users/123")
 *
 *     // Verify request count
 *     shouldHaveBeenCalled(exactly(2)) {
 *         postRequestedFor(urlEqualTo("/api/payments"))
 *     }
 *
 *     // Verify with request body
 *     shouldHaveBeenCalled {
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
 *     stove {
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
 *             shouldHaveBeenCalled(RequestMethod.POST, "/gateway/charge")
 *         }
 *     }
 * }
 * ```
 *
 * ## Configuration
 *
 * ```kotlin
 * Stove()
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
 * @property stove The parent test system.
 * @see WireMockSystemOptions
 */
@WiremockDsl
@Suppress("LargeClass", "TooManyFunctions")
class WireMockSystem(
  override val stove: Stove,
  private val ctx: WireMockContext
) : PluggedSystem,
  ValidatedSystem,
  RunAware,
  ExposesConfiguration,
  Reports {
  override val reportSystemName: String = WireMockReportSystem.name(ctx.keyName)
  private val stubLog: Cache<UUID, StubMapping> = Caffeine.newBuilder().build()
  private val callJournal = WireMockCallJournal()
  private val serde: StoveSerde<Any, ByteArray> = ctx.serde
  private val verification = WireMockVerification(this, callJournal, serde)
  private val reportListener = TestScopeCleanupListener(callJournal::clear)
  private var reportListenerRegistered = false
  private lateinit var exposedConfiguration: WireMockExposedConfiguration

  override fun configuration(): List<String> = ctx.configureExposedConfiguration(exposedConfiguration)

  override fun snapshot(): SystemSnapshot =
    WireMockSnapshotBuilder(reportSystemName, callJournal, wireMock.stubMappings)
      .build(reporter.currentTestId())

  private var wireMock: WireMockServer
  private val logger: Logger = LoggerFactory.getLogger(javaClass)

  init {
    val cfg = wireMockConfig()
      .port(ctx.port)
      .extensions(WireMockRequestListener(stubLog, ctx.afterRequest, callJournal::record))
    val stoveExtensions = mutableListOf<Extension>()
    if (ctx.removeStubAfterRequestMatched) {
      stoveExtensions.add(WireMockVacuumCleaner(stubLog, ctx.afterStubRemoved))
    }
    stoveExtensions.map { cfg.extensions(it) }
    wireMock = WireMockServer(cfg.let(ctx.configure))
    stoveExtensions.filterIsInstance<WireMockVacuumCleaner>().forEach { it.wireMock(wireMock) }
  }

  /**
   * Starts the WireMock server.
   */
  override suspend fun run() {
    if (!reportListenerRegistered) {
      stove.addReportListener(reportListener)
      reportListenerRegistered = true
    }
    wireMock.start()
    exposedConfiguration = WireMockExposedConfiguration(
      host = LOCALHOST,
      port = wireMock.port()
    )
  }

  /**
   * Stops the WireMock server.
   */
  override suspend fun stop(): Unit = wireMock.shutdownServer()

  /**
   * Mocks a GET request with exact URL matching.
   *
   * @param url The exact URL to match.
   * @param statusCode The HTTP status code to return.
   * @param responseBody Optional response body to return.
   * @param metadata Optional metadata to attach to the stub.
   * @param responseHeaders Optional response headers.
   * @return This [WireMockSystem] for chaining.
   */
  suspend fun mockGet(
    url: String,
    statusCode: Int,
    responseBody: Option<Any> = None,
    metadata: Map<String, String> = mapOf(),
    responseHeaders: Map<String, String> = mapOf()
  ): WireMockSystem =
    mockRequest(
      methodName = RequestMethod.GET.value(),
      url = url,
      method = ::get,
      statusCode = statusCode,
      responseBody = responseBody,
      metadata = metadata,
      responseHeaders = responseHeaders,
      reportMetadata = mapOf(STATUS_CODE to statusCode, RESPONSE_HEADERS to responseHeaders)
    )

  /**
   * Mocks a POST request with exact URL and request body matching.
   *
   * The request body must match exactly (ignoring field order but not extra fields).
   * For partial body matching, use [mockPostContaining] instead.
   *
   * @param url The exact URL to match.
   * @param statusCode The HTTP status code to return.
   * @param requestBody Optional request body to match exactly.
   * @param responseBody Optional response body to return.
   * @param metadata Optional metadata to attach to the stub.
   * @param responseHeaders Optional response headers.
   * @return This [WireMockSystem] for chaining.
   * @see mockPostContaining
   */
  suspend fun mockPost(
    url: String,
    statusCode: Int,
    requestBody: Option<Any> = None,
    responseBody: Option<Any> = None,
    metadata: Map<String, Any> = mapOf(),
    responseHeaders: Map<String, String> = mapOf()
  ): WireMockSystem =
    mockRequest(
      methodName = RequestMethod.POST.value(),
      url = url,
      method = ::post,
      statusCode = statusCode,
      requestBody = requestBody,
      responseBody = responseBody,
      metadata = metadata,
      responseHeaders = responseHeaders
    )

  /**
   * Mocks a PUT request with exact URL and request body matching.
   *
   * The request body must match exactly (ignoring field order but not extra fields).
   * For partial body matching, use [mockPutContaining] instead.
   *
   * @param url The exact URL to match.
   * @param statusCode The HTTP status code to return.
   * @param requestBody Optional request body to match exactly.
   * @param responseBody Optional response body to return.
   * @param metadata Optional metadata to attach to the stub.
   * @param responseHeaders Optional response headers.
   * @return This [WireMockSystem] for chaining.
   * @see mockPutContaining
   */
  suspend fun mockPut(
    url: String,
    statusCode: Int,
    requestBody: Option<Any> = None,
    responseBody: Option<Any> = None,
    metadata: Map<String, Any> = mapOf(),
    responseHeaders: Map<String, String> = mapOf()
  ): WireMockSystem =
    mockRequest(
      methodName = RequestMethod.PUT.value(),
      url = url,
      method = ::put,
      statusCode = statusCode,
      requestBody = requestBody,
      responseBody = responseBody,
      metadata = metadata,
      responseHeaders = responseHeaders
    )

  /**
   * Mocks a PATCH request with exact URL and request body matching.
   *
   * The request body must match exactly (ignoring field order but not extra fields).
   * For partial body matching, use [mockPatchContaining] instead.
   *
   * @param url The exact URL to match.
   * @param statusCode The HTTP status code to return.
   * @param requestBody Optional request body to match exactly.
   * @param responseBody Optional response body to return.
   * @param metadata Optional metadata to attach to the stub.
   * @param responseHeaders Optional response headers.
   * @return This [WireMockSystem] for chaining.
   * @see mockPatchContaining
   */
  suspend fun mockPatch(
    url: String,
    statusCode: Int,
    requestBody: Option<Any> = None,
    responseBody: Option<Any> = None,
    metadata: Map<String, Any> = mapOf(),
    responseHeaders: Map<String, String> = mapOf()
  ): WireMockSystem =
    mockRequest(
      methodName = RequestMethod.PATCH.value(),
      url = url,
      method = ::patch,
      statusCode = statusCode,
      requestBody = requestBody,
      responseBody = responseBody,
      metadata = metadata,
      responseHeaders = responseHeaders
    )

  /**
   * Mocks a DELETE request with exact URL matching.
   *
   * @param url The exact URL to match.
   * @param statusCode The HTTP status code to return.
   * @param metadata Optional metadata to attach to the stub.
   * @return This [WireMockSystem] for chaining.
   */
  suspend fun mockDelete(
    url: String,
    statusCode: Int,
    metadata: Map<String, Any> = mapOf()
  ): WireMockSystem =
    mockRequest(
      methodName = RequestMethod.DELETE.value(),
      url = url,
      method = ::delete,
      statusCode = statusCode,
      metadata = metadata
    )

  /**
   * Mocks a HEAD request with exact URL matching.
   *
   * @param url The exact URL to match.
   * @param statusCode The HTTP status code to return.
   * @param metadata Optional metadata to attach to the stub.
   * @return This [WireMockSystem] for chaining.
   */
  suspend fun mockHead(
    url: String,
    statusCode: Int,
    metadata: Map<String, Any> = mapOf()
  ): WireMockSystem =
    mockRequest(
      methodName = RequestMethod.HEAD.value(),
      url = url,
      method = ::head,
      statusCode = statusCode,
      metadata = metadata
    )

  /**
   * Mocks a PUT request with full configuration control.
   *
   * This method provides access to the underlying WireMock [MappingBuilder] for advanced
   * configuration scenarios like custom matchers, headers, or response transformers.
   *
   * @param url The URL or URL pattern to match.
   * @param urlPatternFn Function to create URL pattern. Defaults to exact URL matching.
   *                     Use `{ urlPathMatching(it) }` for regex patterns.
   * @param configure Lambda to configure the request and response using WireMock's API.
   * @return This [WireMockSystem] for chaining.
   */
  suspend fun mockPutConfigure(
    url: String,
    urlPatternFn: (url: String) -> UrlPattern = { urlEqualTo(it) },
    configure: (MappingBuilder, StoveSerde<Any, ByteArray>) -> MappingBuilder
  ): WireMockSystem = mockRequestConfigure(RequestMethod.PUT.value(), url, urlPatternFn, ::put, configure)

  /**
   * Mocks a PATCH request with full configuration control.
   *
   * This method provides access to the underlying WireMock [MappingBuilder] for advanced
   * configuration scenarios like custom matchers, headers, or response transformers.
   *
   * @param url The URL or URL pattern to match.
   * @param urlPatternFn Function to create URL pattern. Defaults to exact URL matching.
   *                     Use `{ urlPathMatching(it) }` for regex patterns.
   * @param configure Lambda to configure the request and response using WireMock's API.
   * @return This [WireMockSystem] for chaining.
   */
  suspend fun mockPatchConfigure(
    url: String,
    urlPatternFn: (url: String) -> UrlPattern = { urlEqualTo(it) },
    configure: (MappingBuilder, StoveSerde<Any, ByteArray>) -> MappingBuilder
  ): WireMockSystem = mockRequestConfigure(RequestMethod.PATCH.value(), url, urlPatternFn, ::patch, configure)

  /**
   * Mocks a GET request with full configuration control.
   *
   * This method provides access to the underlying WireMock [MappingBuilder] for advanced
   * configuration scenarios like custom matchers, headers, or response transformers.
   *
   * @param url The URL or URL pattern to match.
   * @param urlPatternFn Function to create URL pattern. Defaults to exact URL matching.
   *                     Use `{ urlPathMatching(it) }` for regex patterns.
   * @param configure Lambda to configure the request and response using WireMock's API.
   * @return This [WireMockSystem] for chaining.
   */
  suspend fun mockGetConfigure(
    url: String,
    urlPatternFn: (url: String) -> UrlPattern = { urlEqualTo(it) },
    configure: (MappingBuilder, StoveSerde<Any, ByteArray>) -> MappingBuilder
  ): WireMockSystem = mockRequestConfigure(RequestMethod.GET.value(), url, urlPatternFn, ::get, configure)

  /**
   * Mocks a HEAD request with full configuration control.
   *
   * This method provides access to the underlying WireMock [MappingBuilder] for advanced
   * configuration scenarios like custom matchers, headers, or response transformers.
   *
   * @param url The URL or URL pattern to match.
   * @param urlPatternFn Function to create URL pattern. Defaults to exact URL matching.
   *                     Use `{ urlPathMatching(it) }` for regex patterns.
   * @param configure Lambda to configure the request and response using WireMock's API.
   * @return This [WireMockSystem] for chaining.
   */
  suspend fun mockHeadConfigure(
    url: String,
    urlPatternFn: (url: String) -> UrlPattern = { urlEqualTo(it) },
    configure: (MappingBuilder, StoveSerde<Any, ByteArray>) -> MappingBuilder
  ): WireMockSystem = mockRequestConfigure(RequestMethod.HEAD.value(), url, urlPatternFn, ::head, configure)

  /**
   * Mocks a DELETE request with full configuration control.
   *
   * This method provides access to the underlying WireMock [MappingBuilder] for advanced
   * configuration scenarios like custom matchers, headers, or response transformers.
   *
   * @param url The URL or URL pattern to match.
   * @param urlPatternFn Function to create URL pattern. Defaults to exact URL matching.
   *                     Use `{ urlPathMatching(it) }` for regex patterns.
   * @param configure Lambda to configure the request and response using WireMock's API.
   * @return This [WireMockSystem] for chaining.
   */
  suspend fun mockDeleteConfigure(
    url: String,
    urlPatternFn: (url: String) -> UrlPattern = { urlEqualTo(it) },
    configure: (MappingBuilder, StoveSerde<Any, ByteArray>) -> MappingBuilder
  ): WireMockSystem = mockRequestConfigure(RequestMethod.DELETE.value(), url, urlPatternFn, ::delete, configure)

  /**
   * Mocks a POST request with full configuration control.
   *
   * This method provides access to the underlying WireMock [MappingBuilder] for advanced
   * configuration scenarios like custom matchers, headers, or response transformers.
   *
   * @param url The URL or URL pattern to match.
   * @param urlPatternFn Function to create URL pattern. Defaults to exact URL matching.
   *                     Use `{ urlPathMatching(it) }` for regex patterns.
   * @param configure Lambda to configure the request and response using WireMock's API.
   * @return This [WireMockSystem] for chaining.
   */
  suspend fun mockPostConfigure(
    url: String,
    urlPatternFn: (url: String) -> UrlPattern = { urlEqualTo(it) },
    configure: (MappingBuilder, StoveSerde<Any, ByteArray>) -> MappingBuilder
  ): WireMockSystem = mockRequestConfigure(RequestMethod.POST.value(), url, urlPatternFn, ::post, configure)

  /**
   * Configures stateful stub behavior for scenario-based testing.
   *
   * Use this method when you need different responses for the same URL based on
   * the order of requests (e.g., first call returns success, second returns error).
   *
   * ## Example
   *
   * ```kotlin
   * wiremock {
   *     behaviourFor("/api/resource", ::post) { serde ->
   *         initially {
   *             aResponse().withStatus(200).withBody("first response")
   *         }
   *         then {
   *             aResponse().withStatus(500).withBody("server error")
   *         }
   *     }
   * }
   * ```
   *
   * @param url The URL to match.
   * @param method Function to create the HTTP method matcher (e.g., `::post`, `::get`).
   * @param block Lambda to define the sequence of responses.
   */
  suspend fun behaviourFor(
    url: String,
    method: (String) -> MappingBuilder,
    block: StubBehaviourBuilder.(StoveSerde<Any, ByteArray>) -> Unit
  ) {
    report(action = WireMockReportActions.registerBehaviourStub(url)) {
      stubBehaviour(
        wireMockServer = wireMock,
        serde = serde,
        url = url,
        method = method,
        metadata = enrichMetadataWithTestId(emptyMap()),
        recordStub = ::recordStub,
        block = block
      )
    }
  }

  /**
   * Mocks a POST request with partial body matching.
   *
   * Unlike [mockPost], this method allows matching requests where the body
   * **contains** the specified fields, without requiring an exact match of
   * the entire request body. This is useful when you only care about specific
   * fields in the request for test matching purposes.
   *
   * ## Features
   * - **AND logic**: When multiple fields are specified, ALL must match
   * - **Dot notation**: Use `"order.customer.id"` to match deep nested keys
   * - **Partial object matching**: Nested objects match if they contain at least the specified fields
   * - **Multiple fields**: Specify multiple keys to match several fields in one mock
   *
   * ## Examples
   *
   * ```kotlin
   * // Match a top-level field
   * wiremock {
   *     mockPostContaining(
   *         url = "/orders",
   *         requestContaining = mapOf("productId" to 123),
   *         responseBody = OrderResponse(id = "order-1").some()
   *     )
   * }
   *
   * // Match a deeply nested field using dot notation
   * wiremock {
   *     mockPostContaining(
   *         url = "/orders",
   *         requestContaining = mapOf("order.customer.id" to "cust-123"),
   *         responseBody = OrderResponse(id = "order-1").some()
   *     )
   * }
   *
   * // Match multiple fields at different depths
   * wiremock {
   *     mockPostContaining(
   *         url = "/orders",
   *         requestContaining = mapOf(
   *             "order.customer.id" to "cust-123",
   *             "order.payment.method" to "credit_card"
   *         ),
   *         responseBody = OrderResponse(id = "order-1").some()
   *     )
   * }
   * ```
   *
   * @param url The URL to match.
   * @param requestContaining Map of field paths to values. Supports dot notation for nested paths (e.g., "order.customer.id").
   * @param statusCode The HTTP status code to return. Defaults to 200.
   * @param responseBody Optional response body to return.
   * @param metadata Optional metadata to attach to the stub.
   * @param responseHeaders Optional response headers.
   * @param urlPatternFn Function to create URL pattern. Defaults to exact URL matching.
   * @return This [WireMockSystem] for chaining.
   */
  suspend fun mockPostContaining(
    url: String,
    requestContaining: Map<String, Any>,
    statusCode: Int = 200,
    responseBody: Option<Any> = None,
    metadata: Map<String, Any> = mapOf(),
    responseHeaders: Map<String, String> = mapOf(),
    urlPatternFn: (url: String) -> UrlPattern = { urlEqualTo(it) }
  ): WireMockSystem = mockRequestContaining(
    url = url,
    method = ::post,
    requestContaining = requestContaining,
    statusCode = statusCode,
    responseBody = responseBody,
    metadata = metadata,
    responseHeaders = responseHeaders,
    urlPatternFn = urlPatternFn
  )

  /**
   * Mocks a PUT request with partial body matching.
   *
   * Unlike [mockPut], this method allows matching requests where the body
   * **contains** the specified fields, without requiring an exact match of
   * the entire request body. This is useful when you only care about specific
   * fields in the request for test matching purposes.
   *
   * ## Features
   * - **AND logic**: When multiple fields are specified, ALL must match
   * - **Dot notation**: Use `"user.profile.settings.theme"` to match deep nested keys
   * - **Partial object matching**: Nested objects match if they contain at least the specified fields
   * - **Multiple fields**: Specify multiple keys to match several fields in one mock
   *
   * ## Examples
   *
   * ```kotlin
   * // Match a top-level field
   * wiremock {
   *     mockPutContaining(
   *         url = "/users/123",
   *         requestContaining = mapOf("userId" to "user-123"),
   *         responseBody = User(id = "123", name = "Updated").some()
   *     )
   * }
   *
   * // Match a deeply nested field using dot notation
   * wiremock {
   *     mockPutContaining(
   *         url = "/users/123",
   *         requestContaining = mapOf("user.profile.settings.theme" to "dark"),
   *         responseBody = User(id = "123").some()
   *     )
   * }
   * ```
   *
   * @param url The URL to match.
   * @param requestContaining Map of field paths to values. Supports dot notation for nested paths (e.g., "user.profile.id").
   * @param statusCode The HTTP status code to return. Defaults to 200.
   * @param responseBody Optional response body to return.
   * @param metadata Optional metadata to attach to the stub.
   * @param responseHeaders Optional response headers.
   * @param urlPatternFn Function to create URL pattern. Defaults to exact URL matching.
   * @return This [WireMockSystem] for chaining.
   */
  suspend fun mockPutContaining(
    url: String,
    requestContaining: Map<String, Any>,
    statusCode: Int = 200,
    responseBody: Option<Any> = None,
    metadata: Map<String, Any> = mapOf(),
    responseHeaders: Map<String, String> = mapOf(),
    urlPatternFn: (url: String) -> UrlPattern = { urlEqualTo(it) }
  ): WireMockSystem = mockRequestContaining(
    url = url,
    method = ::put,
    requestContaining = requestContaining,
    statusCode = statusCode,
    responseBody = responseBody,
    metadata = metadata,
    responseHeaders = responseHeaders,
    urlPatternFn = urlPatternFn
  )

  /**
   * Mocks a PATCH request with partial body matching.
   *
   * Unlike [mockPatch], this method allows matching requests where the body
   * **contains** the specified fields, without requiring an exact match of
   * the entire request body. This is useful when you only care about specific
   * fields in the request for test matching purposes.
   *
   * ## Features
   * - **AND logic**: When multiple fields are specified, ALL must match
   * - **Dot notation**: Use `"document.section.text"` to match deep nested keys
   * - **Partial object matching**: Nested objects match if they contain at least the specified fields
   * - **Multiple fields**: Specify multiple keys to match several fields in one mock
   *
   * ## Examples
   *
   * ```kotlin
   * // Match a top-level field
   * wiremock {
   *     mockPatchContaining(
   *         url = "/users/123",
   *         requestContaining = mapOf("status" to "active"),
   *         responseBody = User(id = "123", status = "active").some()
   *     )
   * }
   *
   * // Match a deeply nested field using dot notation
   * wiremock {
   *     mockPatchContaining(
   *         url = "/documents/123",
   *         requestContaining = mapOf("document.section.paragraph.text" to "updated"),
   *         responseBody = Document(id = "123").some()
   *     )
   * }
   * ```
   *
   * @param url The URL to match.
   * @param requestContaining Map of field paths to values. Supports dot notation for nested paths (e.g., "config.settings.enabled").
   * @param statusCode The HTTP status code to return. Defaults to 200.
   * @param responseBody Optional response body to return.
   * @param metadata Optional metadata to attach to the stub.
   * @param responseHeaders Optional response headers.
   * @param urlPatternFn Function to create URL pattern. Defaults to exact URL matching.
   * @return This [WireMockSystem] for chaining.
   */
  suspend fun mockPatchContaining(
    url: String,
    requestContaining: Map<String, Any>,
    statusCode: Int = 200,
    responseBody: Option<Any> = None,
    metadata: Map<String, Any> = mapOf(),
    responseHeaders: Map<String, String> = mapOf(),
    urlPatternFn: (url: String) -> UrlPattern = { urlEqualTo(it) }
  ): WireMockSystem = mockRequestContaining(
    url = url,
    method = ::patch,
    requestContaining = requestContaining,
    statusCode = statusCode,
    responseBody = responseBody,
    metadata = metadata,
    responseHeaders = responseHeaders,
    urlPatternFn = urlPatternFn
  )

  /**
   * Verifies that a request matching the provided criteria has been called.
   *
   * By default, the request must have been called exactly once. Use WireMock's count helpers
   * such as [moreThanOrExactly], [lessThan], or [exactly] to customize the expected count.
   */
  suspend fun shouldHaveBeenCalled(
    method: RequestMethod,
    url: String,
    count: CountMatchingStrategy = exactly(1),
    requestBody: Option<Any> = None,
    requestContaining: Map<String, Any> = emptyMap(),
    headers: Map<String, String> = emptyMap(),
    queryParams: Map<String, String> = emptyMap(),
    urlPatternFn: (url: String) -> UrlPattern = { urlEqualTo(it) }
  ): WireMockSystem =
    verification.shouldHaveBeenCalled(
      method = method,
      url = url,
      count = count,
      requestBody = requestBody,
      requestContaining = requestContaining,
      headers = headers,
      queryParams = queryParams,
      urlPatternFn = urlPatternFn
    )

  /**
   * Verifies that a request matching the provided WireMock pattern has been called.
   *
   * By default, the request must have been called exactly once.
   */
  suspend fun shouldHaveBeenCalled(
    count: CountMatchingStrategy = exactly(1),
    request: @WiremockDsl () -> RequestPatternBuilder
  ): WireMockSystem =
    verification.shouldHaveBeenCalled(count, request)

  /**
   * Verifies that no request matching the provided criteria has been called.
   */
  suspend fun shouldNotHaveBeenCalled(
    method: RequestMethod,
    url: String,
    requestBody: Option<Any> = None,
    requestContaining: Map<String, Any> = emptyMap(),
    headers: Map<String, String> = emptyMap(),
    queryParams: Map<String, String> = emptyMap(),
    urlPatternFn: (url: String) -> UrlPattern = { urlEqualTo(it) }
  ): WireMockSystem =
    verification.shouldNotHaveBeenCalled(
      method = method,
      url = url,
      requestBody = requestBody,
      requestContaining = requestContaining,
      headers = headers,
      queryParams = queryParams,
      urlPatternFn = urlPatternFn
    )

  /**
   * Verifies that no request matching the provided WireMock pattern has been called.
   */
  suspend fun shouldNotHaveBeenCalled(
    request: @WiremockDsl () -> RequestPatternBuilder
  ): WireMockSystem =
    verification.shouldNotHaveBeenCalled(request)

  /**
   * Returns requests from the current test matching the provided criteria.
   */
  fun callsFor(
    method: RequestMethod,
    url: String,
    requestBody: Option<Any> = None,
    requestContaining: Map<String, Any> = emptyMap(),
    headers: Map<String, String> = emptyMap(),
    queryParams: Map<String, String> = emptyMap(),
    urlPatternFn: (url: String) -> UrlPattern = { urlEqualTo(it) }
  ): List<LoggedRequest> =
    verification.callsFor(
      method = method,
      url = url,
      requestBody = requestBody,
      requestContaining = requestContaining,
      headers = headers,
      queryParams = queryParams,
      urlPatternFn = urlPatternFn
    )

  /**
   * Returns requests from the current test matching the provided WireMock pattern.
   */
  fun callsFor(
    request: @WiremockDsl () -> RequestPatternBuilder
  ): List<LoggedRequest> = verification.callsFor(request)

  private suspend fun mockRequest(
    methodName: String,
    url: String,
    method: (UrlPattern) -> MappingBuilder,
    statusCode: Int,
    requestBody: Option<Any> = None,
    responseBody: Option<Any> = None,
    metadata: Map<String, Any> = emptyMap(),
    responseHeaders: Map<String, String> = emptyMap(),
    reportMetadata: Map<String, Any> = mapOf(STATUS_CODE to statusCode)
  ): WireMockSystem =
    mockRequest(
      action = WireMockReportActions.registerStub(methodName, url),
      request = method(urlEqualTo(url)),
      statusCode = statusCode,
      requestBody = requestBody,
      responseBody = responseBody,
      metadata = metadata,
      responseHeaders = responseHeaders,
      reportMetadata = reportMetadata
    )

  private suspend fun mockRequest(
    action: String,
    request: MappingBuilder,
    statusCode: Int,
    requestBody: Option<Any> = None,
    responseBody: Option<Any> = None,
    metadata: Map<String, Any> = emptyMap(),
    responseHeaders: Map<String, String> = emptyMap(),
    reportMetadata: Map<String, Any> = mapOf(STATUS_CODE to statusCode)
  ): WireMockSystem =
    registerStub(
      action = action,
      input = requestBody,
      metadata = reportMetadata
    ) {
      configureBodyAndMetadata(request, metadata, requestBody)
      request.willReturn(configureResponse(statusCode, responseBody, responseHeaders))
    }

  private suspend fun mockRequestConfigure(
    methodName: String,
    url: String,
    urlPatternFn: (url: String) -> UrlPattern,
    method: (UrlPattern) -> MappingBuilder,
    configure: (MappingBuilder, StoveSerde<Any, ByteArray>) -> MappingBuilder
  ): WireMockSystem =
    registerStub(action = WireMockReportActions.registerCustomStub(methodName, url)) {
      val configuredRequest = configure(method(urlPatternFn(url)), serde)
      configuredRequest.withMetadata(enrichMetadataWithTestId(emptyMap()))
      configuredRequest
    }

  private suspend fun mockRequestContaining(
    url: String,
    method: (UrlPattern) -> MappingBuilder,
    requestContaining: Map<String, Any>,
    statusCode: Int,
    responseBody: Option<Any>,
    metadata: Map<String, Any>,
    responseHeaders: Map<String, String>,
    urlPatternFn: (url: String) -> UrlPattern
  ): WireMockSystem {
    require(requestContaining.isNotEmpty()) { WireMockValidationMessages.REQUEST_CONTAINING_EMPTY }

    return registerStub(
      action = WireMockReportActions.registerPartialStub(url),
      input = requestContaining.some(),
      metadata = mapOf(STATUS_CODE to statusCode)
    ) {
      val mockRequest = method(urlPatternFn(url))
      mockRequest.withMetadata(enrichMetadataWithTestId(metadata))
      mockRequest.withHeader(CONTENT_TYPE, ContainsPattern(APPLICATION_JSON))
      mockRequest.configureBodyContaining(requestContaining, serde)
      val mockResponse = configureResponse(statusCode, responseBody, responseHeaders)
      mockRequest.willReturn(mockResponse)
    }
  }

  /**
   * Validates that all registered stubs were matched by incoming requests.
   *
   * If any requests were received that didn't match a stub, this method throws
   * an [AssertionError] with details about the unmatched requests.
   *
   * This is typically called at the end of a test to ensure all expected
   * external service calls were properly mocked.
   *
   * @throws AssertionError if there are unmatched requests.
   */
  override suspend fun validate() {
    val currentTestId = reporter.currentTestId()

    // Fail-open scoping: the journal excludes a request only when it is provably
    // tagged with another test id; untagged unmatched requests fail every test.
    val unmatched = callJournal
      .serveEvents(currentTestId)
      .filterNot { it.wasMatched }
      .map { it.request }
    val passed = unmatched.isEmpty()

    if (!passed) {
      val problems = unmatched.joinToString("\n") {
        WireMockValidationMessages.unmatchedRequestDetails(
          url = "${it.method.value()} ${it.url}",
          bodyAsString = it.bodyAsString,
          queryParams = serde.serialize(it.queryParams).decodeToString()
        )
      }
      val error = AssertionError(
        WireMockValidationMessages.unmatchedRequests(problems)
      )

      reporter.record(
        ReportEntry.failure(
          system = reportSystemName,
          testId = reporter.currentTestId(),
          action = VALIDATE_ALL_REQUESTS_SHOULD_MATCH,
          error = error.message ?: WireMockValidationMessages.VALIDATION_FAILED,
          expected = WireMockValidationMessages.EXPECTED_NO_UNMATCHED_REQUESTS.some(),
          actual = WireMockValidationMessages.unmatchedRequestCount(unmatched.size).some()
        )
      )

      throw error
    } else {
      reporter.record(
        ReportEntry.success(
          system = reportSystemName,
          testId = reporter.currentTestId(),
          action = VALIDATE_ALL_REQUESTS_MATCHED
        )
      )
    }
  }

  /**
   * Closes the WireMock system and stops the server.
   */
  override fun close(): Unit = runBlocking {
    Try {
      if (reportListenerRegistered) {
        stove.removeReportListener(reportListener)
        reportListenerRegistered = false
      }
      stop()
      callJournal.clearAll()
    }.recover { logger.warn("${WireMockValidationMessages.STOP_FAILED_PREFIX} ${it.message}") }
  }

  private suspend fun registerStub(
    action: String,
    input: Option<Any> = None,
    metadata: Map<String, Any> = emptyMap(),
    request: () -> MappingBuilder
  ): WireMockSystem {
    report(action = action, input = input, metadata = metadata) {
      registerStub(request())
    }
    return this
  }

  private fun registerStub(request: MappingBuilder) {
    val stub = wireMock.stubFor(request.withId(UUID.randomUUID()))
    recordStub(stub)
  }

  private fun recordStub(stub: StubMapping) {
    stubLog.put(stub.id, stub)
    callJournal.recordStub(stub)
  }

  private fun enrichMetadataWithTestId(metadata: Map<String, Any>): Map<String, Any> =
    reporter.currentTestIdOrNull()?.let { metadata + (STOVE_TEST_ID_KEY to it) } ?: metadata

  private fun configureBodyAndMetadata(
    request: MappingBuilder,
    metadata: Map<String, Any>,
    body: Option<Any>
  ) {
    request.withMetadata(enrichMetadataWithTestId(metadata))
    body.map {
      request
        .withRequestBody(
          equalToJson(
            serde.serialize(it).decodeToString(),
            true,
            false
          )
        ).withHeader(CONTENT_TYPE, ContainsPattern(APPLICATION_JSON))
    }
  }

  private fun configureResponse(
    statusCode: Int,
    responseBody: Option<Any>,
    responseHeaders: Map<String, String>
  ): ResponseDefinitionBuilder? {
    val mockResponse = aResponse()
      .withStatus(statusCode)
      .withHeader(CONTENT_TYPE, APPLICATION_JSON_UTF8)
    responseHeaders.forEach {
      mockResponse.withHeader(it.key, it.value)
    }
    responseBody.map { mockResponse.withBody(serde.serialize(it)) }
    return mockResponse
  }

  companion object {
    /**
     * Metadata key used to associate stubs with test IDs for filtering in snapshots.
     */
    const val STOVE_TEST_ID_KEY = "stoveTestId"
    private const val LOCALHOST = "localhost"

    /**
     * Exposes the [WireMockServer] instance for the given [WireMockSystem].
     * Use this for advanced WireMock operations not covered by the DSL.
     */
    @Suppress("unused")
    fun WireMockSystem.server(): WireMockServer = wireMock
  }
}
