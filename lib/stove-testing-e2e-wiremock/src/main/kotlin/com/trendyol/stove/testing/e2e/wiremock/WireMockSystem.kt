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

  /**
   * Starts the WireMock server.
   */
  override suspend fun run(): Unit = wireMock.start()

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

  /**
   * Mocks a DELETE request with exact URL matching.
   *
   * @param url The exact URL to match.
   * @param statusCode The HTTP status code to return.
   * @param metadata Optional metadata to attach to the stub.
   * @return This [WireMockSystem] for chaining.
   */
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

  /**
   * Mocks a HEAD request with exact URL matching.
   *
   * @param url The exact URL to match.
   * @param statusCode The HTTP status code to return.
   * @param metadata Optional metadata to attach to the stub.
   * @return This [WireMockSystem] for chaining.
   */
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
  @WiremockDsl
  fun behaviourFor(
    url: String,
    method: (String) -> MappingBuilder,
    block: StubBehaviourBuilder.(StoveSerde<Any, ByteArray>) -> Unit
  ) {
    stubBehaviour(wireMock, serde = serde, url, method, block)
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
  @WiremockDsl
  fun mockPostContaining(
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
  @WiremockDsl
  fun mockPutContaining(
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
  @WiremockDsl
  fun mockPatchContaining(
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

  private fun mockRequestContaining(
    url: String,
    method: (UrlPattern) -> MappingBuilder,
    requestContaining: Map<String, Any>,
    statusCode: Int,
    responseBody: Option<Any>,
    metadata: Map<String, Any>,
    responseHeaders: Map<String, String>,
    urlPatternFn: (url: String) -> UrlPattern
  ): WireMockSystem {
    require(requestContaining.isNotEmpty()) { "requestContaining must not be empty" }

    val mockRequest = method(urlPatternFn(url))
    mockRequest.withMetadata(metadata)
    mockRequest.withHeader("Content-Type", ContainsPattern("application/json"))

    configureBodyContaining(mockRequest, requestContaining)

    val mockResponse = configureResponse(statusCode, responseBody, responseHeaders)
    val stub = wireMock.stubFor(mockRequest.willReturn(mockResponse).withId(UUID.randomUUID()))
    stubLog.put(stub.id, stub)
    return this
  }

  private fun configureBodyContaining(
    request: MappingBuilder,
    requestContaining: Map<String, Any>
  ) {
    requestContaining.forEach { (key, value) ->
      val matcher = createValueMatcher(value)
      // Support dot notation for nested paths (e.g., "order.customer.id" -> "$.order.customer.id")
      val jsonPath = "$.$key"
      request.withRequestBody(matchingJsonPath(jsonPath, matcher))
    }
  }

  private fun createValueMatcher(value: Any): StringValuePattern = when (value) {
    is String -> {
      equalTo(value)
    }

    is Number -> {
      equalTo(value.toString())
    }

    is Boolean -> {
      equalTo(value.toString())
    }

    is Map<*, *> -> {
      // For nested objects, use equalToJson with ignoreExtraElements=true for deep partial matching
      val serialized = serde.serialize(value).decodeToString()
      equalToJson(serialized, true, true)
    }

    is Collection<*> -> {
      // For arrays, use equalToJson with ignoreArrayOrder=true and ignoreExtraElements=true
      val serialized = serde.serialize(value).decodeToString()
      equalToJson(serialized, true, true)
    }

    else -> {
      // For other objects, serialize and use equalToJson
      val serialized = serde.serialize(value).decodeToString()
      equalToJson(serialized, true, true)
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

  /**
   * Closes the WireMock system and stops the server.
   */
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
