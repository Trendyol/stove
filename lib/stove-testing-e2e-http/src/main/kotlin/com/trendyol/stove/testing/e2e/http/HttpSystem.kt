@file:Suppress("MemberVisibilityCanBePrivate")

package com.trendyol.stove.testing.e2e.http

import arrow.core.*
import com.trendyol.stove.testing.e2e.serialization.StoveSerde
import com.trendyol.stove.testing.e2e.system.*
import com.trendyol.stove.testing.e2e.system.abstractions.*
import com.trendyol.stove.testing.e2e.system.annotations.StoveDsl
import io.ktor.client.call.*
import io.ktor.client.plugins.websocket.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.*
import io.ktor.serialization.jackson.*
import io.ktor.util.*
import io.ktor.util.reflect.*
import kotlinx.coroutines.flow.Flow
import java.nio.charset.Charset
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Configuration options for the HTTP client system.
 *
 * ## Basic Configuration
 *
 * ```kotlin
 * TestSystem()
 *     .with {
 *         httpClient {
 *             HttpClientSystemOptions(
 *                 baseUrl = "http://localhost:8080"
 *             )
 *         }
 *     }
 * ```
 *
 * ## Custom Serialization
 *
 * Match your application's JSON serialization:
 *
 * ```kotlin
 * httpClient {
 *     HttpClientSystemOptions(
 *         baseUrl = "http://localhost:8080",
 *         contentConverter = JacksonConverter(myObjectMapper),
 *         timeout = 60.seconds
 *     )
 * }
 * ```
 *
 * ## Custom HTTP Client
 *
 * For advanced scenarios (custom SSL, interceptors, etc.):
 *
 * ```kotlin
 * httpClient {
 *     HttpClientSystemOptions(
 *         baseUrl = "http://localhost:8080",
 *         createClient = { baseUrl ->
 *             HttpClient(CIO) {
 *                 install(ContentNegotiation) { jackson() }
 *                 install(Logging) { level = LogLevel.ALL }
 *                 defaultRequest { url(baseUrl) }
 *             }
 *         }
 *     )
 * }
 * ```
 *
 * @property baseUrl The base URL for all HTTP requests (e.g., "http://localhost:8080").
 * @property contentConverter The content converter for JSON serialization (default: Jackson).
 * @property timeout Request timeout duration (default: 30 seconds).
 * @property createClient Factory function for creating the underlying Ktor HTTP client.
 */
@HttpDsl
data class HttpClientSystemOptions(
  val baseUrl: String,
  val contentConverter: ContentConverter = JacksonConverter(StoveSerde.jackson.default),
  val webSocketContentConverter: WebsocketContentConverter = JacksonWebsocketContentConverter(
    StoveSerde.jackson.default
  ),
  val timeout: Duration = 30.seconds,
  val wsPingInterval: Duration = 20.seconds,
  val configureClient: io.ktor.client.HttpClientConfig<*>.() -> Unit = {},
  val configureWebSocket: WebSockets.Config.() -> Unit = {},
  val createClient: (
    baseUrl: String
  ) -> io.ktor.client.HttpClient = { url ->
    jsonHttpClient(
      url,
      timeout,
      contentConverter,
      webSocketContentConverter,
      wsPingInterval,
      configureWebSocket,
      configureClient
    )
  }
) : SystemOptions

internal fun TestSystem.withHttpClient(options: HttpClientSystemOptions): TestSystem {
  this.getOrRegister(HttpSystem(this, options))
  return this
}

internal fun TestSystem.http(): HttpSystem = getOrNone<HttpSystem>().getOrElse {
  throw SystemNotRegisteredException(HttpSystem::class)
}

/**
 * Registers the HTTP client system with the test system.
 *
 * ```kotlin
 * TestSystem()
 *     .with {
 *         httpClient {
 *             HttpClientSystemOptions(baseUrl = "http://localhost:8080")
 *         }
 *     }
 * ```
 *
 * @param configure Configuration block returning [HttpClientSystemOptions].
 * @return The test system for fluent chaining.
 */
@StoveDsl
fun WithDsl.httpClient(configure: @StoveDsl () -> HttpClientSystemOptions): TestSystem =
  this.testSystem.withHttpClient(configure())

/**
 * Executes HTTP assertions within the validation DSL.
 *
 * ```kotlin
 * TestSystem.validate {
 *     http {
 *         get<UserResponse>("/users/123") { user ->
 *             user.name shouldBe "John"
 *         }
 *     }
 * }
 * ```
 *
 * @param validation The HTTP assertion block.
 */
@StoveDsl
suspend fun ValidationDsl.http(
  validation: @HttpDsl suspend HttpSystem.() -> Unit
): Unit = validation(this.testSystem.http())

/**
 * HTTP client system for testing REST APIs.
 *
 * Provides a fluent DSL for making HTTP requests and asserting responses.
 * All methods return the system instance for chaining.
 *
 * ## GET Requests
 *
 * ```kotlin
 * http {
 *     // Get with typed response body
 *     get<UserResponse>("/users/123") { user ->
 *         user.name shouldBe "John"
 *         user.email shouldBe "john@example.com"
 *     }
 *
 *     // Get with query parameters
 *     get<List<UserResponse>>("/users", queryParams = mapOf("role" to "admin")) { users ->
 *         users.size shouldBeGreaterThan 0
 *     }
 *
 *     // Get with full response access
 *     getResponse<UserResponse>("/users/123") { response ->
 *         response.status shouldBe 200
 *         response.headers["Content-Type"] shouldContain "application/json"
 *         response.body().name shouldBe "John"
 *     }
 *
 *     // Get with headers and auth token
 *     get<SecretData>(
 *         uri = "/secrets",
 *         headers = mapOf("X-Request-Id" to "123"),
 *         token = "jwt-token".some()
 *     ) { data ->
 *         data shouldNotBe null
 *     }
 * }
 * ```
 *
 * ## POST Requests
 *
 * ```kotlin
 * http {
 *     // Post with JSON body and typed response
 *     postAndExpectJson<UserResponse>(
 *         uri = "/users",
 *         body = CreateUserRequest(name = "John", email = "john@example.com").some()
 *     ) { user ->
 *         user.id shouldNotBe null
 *     }
 *
 *     // Post expecting only status code
 *     postAndExpectBodilessResponse(
 *         uri = "/users",
 *         body = CreateUserRequest(name = "John").some()
 *     ) { response ->
 *         response.status shouldBe 201
 *     }
 *
 *     // Post with full response access
 *     postAndExpectBody<UserResponse>(
 *         uri = "/users",
 *         body = request.some()
 *     ) { response ->
 *         response.status shouldBe 201
 *         response.body().id shouldNotBe null
 *     }
 * }
 * ```
 *
 * ## PUT, PATCH, DELETE Requests
 *
 * ```kotlin
 * http {
 *     // PUT
 *     putAndExpectJson<UserResponse>(
 *         uri = "/users/123",
 *         body = UpdateUserRequest(name = "Jane").some()
 *     ) { user ->
 *         user.name shouldBe "Jane"
 *     }
 *
 *     // PATCH
 *     patchAndExpectBodilessResponse(
 *         uri = "/users/123",
 *         body = mapOf("status" to "active").some()
 *     ) { response ->
 *         response.status shouldBe 200
 *     }
 *
 *     // DELETE
 *     deleteAndExpectBodilessResponse("/users/123") { response ->
 *         response.status shouldBe 204
 *     }
 * }
 * ```
 *
 * ## Multipart/Form Requests
 *
 * ```kotlin
 * http {
 *     postMultipartAndExpectResponse<UploadResponse>(
 *         uri = "/upload",
 *         body = listOf(
 *             StoveMultiPartContent.Text("name", "document.pdf"),
 *             StoveMultiPartContent.File(
 *                 param = "file",
 *                 fileName = "document.pdf",
 *                 content = fileBytes,
 *                 contentType = "application/pdf"
 *             )
 *         )
 *     ) { response ->
 *         response.body().fileId shouldNotBe null
 *     }
 * }
 * ```
 *
 * ## Streaming Responses
 *
 * ```kotlin
 * http {
 *     readJsonStream<LogEntry>(
 *         uri = "/logs/stream",
 *         headers = mapOf("Accept" to "application/x-ndjson")
 *     ) { flow ->
 *         flow.collect { entry ->
 *             println(entry.message)
 *         }
 *     }
 * }
 * ```
 *
 * @property testSystem The parent test system.
 * @property options HTTP client configuration options.
 * @see HttpClientSystemOptions
 * @see StoveHttpResponse
 */
@Suppress("TooManyFunctions")
@HttpDsl
class HttpSystem(
  override val testSystem: TestSystem,
  @PublishedApi internal val options: HttpClientSystemOptions
) : PluggedSystem {
  @PublishedApi
  internal val ktorHttpClient: io.ktor.client.HttpClient = options.createClient(options.baseUrl)

  @HttpDsl
  suspend fun getResponse(
    uri: String,
    queryParams: Map<String, String> = mapOf(),
    headers: Map<String, String> = mapOf(),
    token: Option<String> = None,
    expect: suspend (StoveHttpResponse) -> Unit
  ): HttpSystem = get(uri, headers, queryParams, token)
    .also { expect(it.toBodilessResponse()) }
    .let { this }

  @HttpDsl
  suspend inline fun <reified T : Any> getResponse(
    uri: String,
    queryParams: Map<String, String> = mapOf(),
    headers: Map<String, String> = mapOf(),
    token: Option<String> = None,
    expect: (StoveHttpResponse.WithBody<T>) -> Unit
  ): HttpSystem = get(uri, headers, queryParams, token)
    .also { expect(it.toResponseWithBody()) }
    .let { this }

  @HttpDsl
  suspend inline fun <reified TExpected : Any> get(
    uri: String,
    queryParams: Map<String, String> = mapOf(),
    headers: Map<String, String> = mapOf(),
    token: Option<String> = None,
    expect: (TExpected) -> Unit
  ): HttpSystem = get(uri, headers, queryParams, token)
    .also { it.expectSuccessBody(expect) }
    .let { this }

  @HttpDsl
  suspend inline fun <reified TExpected : Any> getMany(
    uri: String,
    queryParams: Map<String, String> = mapOf(),
    headers: Map<String, String> = mapOf(),
    token: Option<String> = None,
    expect: (List<TExpected>) -> Unit
  ): HttpSystem = get(uri, headers, queryParams, token)
    .also { it.expectSuccessBody(expect) }
    .let { this }

  suspend inline fun <reified TExpected : Any> readJsonStream(
    uri: String,
    queryParams: Map<String, String> = mapOf(),
    headers: Map<String, String> = mapOf(),
    token: Option<String> = None,
    expect: (Flow<TExpected>) -> Unit
  ): HttpSystem = ktorHttpClient
    .prepareGet {
      url { appendEncodedPathSegments(uri) }
      headers.forEach { (key, value) -> header(key, value) }
      header(HttpHeaders.Accept, "application/x-ndjson")
      queryParams.forEach { (key, value) -> parameter(key, value) }
      token.map { header(HeaderConstants.AUTHORIZATION, HeaderConstants.bearer(it)) }
    }.readJsonContentStream {
      options.contentConverter.deserialize(Charset.defaultCharset(), typeInfo<TExpected>(), it) as TExpected
    }.also { expect(it) }
    .let { return this }

  @HttpDsl
  suspend fun postAndExpectBodilessResponse(
    uri: String,
    body: Option<Any>,
    token: Option<String> = None,
    headers: Map<String, String> = mapOf(),
    expect: suspend (StoveHttpResponse) -> Unit
  ): HttpSystem = executeWithBody(HttpMethod.Post, uri, body, headers, token)
    .also { expect(it.toBodilessResponse()) }
    .let { this }

  @HttpDsl
  suspend inline fun <reified TExpected : Any> postAndExpectJson(
    uri: String,
    body: Option<Any> = None,
    headers: Map<String, String> = mapOf(),
    token: Option<String> = None,
    expect: (actual: TExpected) -> Unit
  ): HttpSystem = executeWithBody(HttpMethod.Post, uri, body, headers, token)
    .also { it.expectSuccessBody(expect) }
    .let { this }

  /**
   * Posts the given [body] to the given [uri] and expects the response to have a body.
   */
  @HttpDsl
  suspend inline fun <reified TExpected : Any> postAndExpectBody(
    uri: String,
    body: Option<Any> = None,
    headers: Map<String, String> = mapOf(),
    token: Option<String> = None,
    expect: (actual: StoveHttpResponse.WithBody<TExpected>) -> Unit
  ): HttpSystem = executeWithBody(HttpMethod.Post, uri, body, headers, token)
    .also { expect(it.toResponseWithBody()) }
    .let { this }

  @HttpDsl
  suspend fun putAndExpectBodilessResponse(
    uri: String,
    body: Option<Any>,
    token: Option<String> = None,
    headers: Map<String, String> = mapOf(),
    expect: suspend (StoveHttpResponse) -> Unit
  ): HttpSystem = executeWithBody(HttpMethod.Put, uri, body, headers, token)
    .also { expect(it.toBodilessResponse()) }
    .let { this }

  @HttpDsl
  suspend inline fun <reified TExpected : Any> putAndExpectJson(
    uri: String,
    body: Option<Any> = None,
    headers: Map<String, String> = mapOf(),
    token: Option<String> = None,
    expect: (actual: TExpected) -> Unit
  ): HttpSystem = executeWithBody(HttpMethod.Put, uri, body, headers, token)
    .also { it.expectSuccessBody(expect) }
    .let { this }

  @HttpDsl
  suspend inline fun <reified TExpected : Any> putAndExpectBody(
    uri: String,
    body: Option<Any> = None,
    headers: Map<String, String> = mapOf(),
    token: Option<String> = None,
    expect: (actual: StoveHttpResponse.WithBody<TExpected>) -> Unit
  ): HttpSystem = executeWithBody(HttpMethod.Put, uri, body, headers, token)
    .also { expect(it.toResponseWithBody()) }
    .let { this }

  @HttpDsl
  suspend fun patchAndExpectBodilessResponse(
    uri: String,
    body: Option<Any>,
    token: Option<String> = None,
    headers: Map<String, String> = mapOf(),
    expect: suspend (StoveHttpResponse) -> Unit
  ): HttpSystem = executeWithBody(HttpMethod.Patch, uri, body, headers, token)
    .also { expect(it.toBodilessResponse()) }
    .let { this }

  @HttpDsl
  suspend inline fun <reified TExpected : Any> patchAndExpectJson(
    uri: String,
    body: Option<Any> = None,
    headers: Map<String, String> = mapOf(),
    token: Option<String> = None,
    expect: (actual: TExpected) -> Unit
  ): HttpSystem = executeWithBody(HttpMethod.Patch, uri, body, headers, token)
    .also { it.expectSuccessBody(expect) }
    .let { this }

  @HttpDsl
  suspend inline fun <reified TExpected : Any> patchAndExpectBody(
    uri: String,
    body: Option<Any> = None,
    headers: Map<String, String> = mapOf(),
    token: Option<String> = None,
    expect: (actual: StoveHttpResponse.WithBody<TExpected>) -> Unit
  ): HttpSystem = executeWithBody(HttpMethod.Patch, uri, body, headers, token)
    .also { expect(it.toResponseWithBody()) }
    .let { this }

  @HttpDsl
  suspend fun deleteAndExpectBodilessResponse(
    uri: String,
    token: Option<String> = None,
    headers: Map<String, String> = mapOf(),
    expect: suspend (StoveHttpResponse) -> Unit
  ): HttpSystem = ktorHttpClient
    .delete {
      configureRequest(uri, headers, token)
    }.also { expect(it.toBodilessResponse()) }
    .let { this }

  @HttpDsl
  suspend inline fun <reified TExpected : Any> postMultipartAndExpectResponse(
    uri: String,
    body: List<StoveMultiPartContent>,
    headers: Map<String, String> = mapOf(),
    token: Option<String> = None,
    expect: (StoveHttpResponse.WithBody<TExpected>) -> Unit
  ): HttpSystem = ktorHttpClient
    .submitForm {
      configureRequest(uri, headers, token)
      setBody(MultiPartFormDataContent(toFormData(body)))
    }.also { expect(it.toResponseWithBody()) }
    .let { this }

  @HttpDsl
  override fun then(): TestSystem = testSystem

  @PublishedApi
  internal suspend fun get(
    uri: String,
    headers: Map<String, String>,
    queryParams: Map<String, String>,
    token: Option<String>
  ) = ktorHttpClient.get {
    configureRequest(uri, headers, token)
    queryParams.forEach { (key, value) -> parameter(key, value) }
  }

  @PublishedApi
  internal suspend fun executeWithBody(
    method: HttpMethod,
    uri: String,
    body: Option<Any>,
    headers: Map<String, String>,
    token: Option<String>
  ): HttpResponse = ktorHttpClient.request {
    this.method = method
    configureRequest(uri, headers, token)
    body.map { setBody(it) }
  }

  @PublishedApi
  internal fun HttpRequestBuilder.configureRequest(
    uri: String,
    headers: Map<String, String>,
    token: Option<String>
  ) {
    url { appendEncodedPathSegments(uri) }
    headers.forEach { (key, value) -> header(key, value) }
    token.map { header(HeaderConstants.AUTHORIZATION, HeaderConstants.bearer(it)) }
  }

  @PublishedApi
  internal fun HttpResponse.toBodilessResponse(): StoveHttpResponse.Bodiless =
    StoveHttpResponse.Bodiless(status.value, headers.toMap())

  @PublishedApi
  internal inline fun <reified T : Any> HttpResponse.toResponseWithBody(): StoveHttpResponse.WithBody<T> =
    StoveHttpResponse.WithBody(status.value, headers.toMap()) { body() }

  @PublishedApi
  internal suspend inline fun <reified T : Any> HttpResponse.expectSuccessBody(expect: (T) -> Unit) {
    check(status.isSuccess()) { "Expected a successful response, but got $status" }
    expect(body())
  }

  @PublishedApi
  internal fun toFormData(
    body: List<StoveMultiPartContent>
  ) = formData {
    body.forEach {
      when (it) {
        is StoveMultiPartContent.Text -> append(it.param, it.value)

        is StoveMultiPartContent.Binary -> append(
          it.param,
          it.content,
          Headers.build {
            append(HttpHeaders.ContentType, ContentType.Application.OctetStream)
          }
        )

        is StoveMultiPartContent.File -> append(
          it.param,
          it.content,
          Headers.build {
            append(HttpHeaders.ContentType, ContentType.parse(it.contentType))
            append(HttpHeaders.ContentDisposition, "filename=${it.fileName}")
          }
        )
      }
    }
  }

  override fun close() {
    ktorHttpClient.close()
  }

  companion object {
    object HeaderConstants {
      const val AUTHORIZATION = "Authorization"

      fun bearer(token: String) = "Bearer $token"
    }

    /**
     * Exposes the [io.ktor.client.HttpClient] used by the [HttpSystem].
     */
    @Suppress("unused")
    @HttpDsl
    fun HttpSystem.client(): io.ktor.client.HttpClient = this.ktorHttpClient

    /**
     * Exposes the [io.ktor.client.HttpClient] used by the [HttpSystem].
     */
    @Suppress("unused")
    @HttpDsl
    suspend fun HttpSystem.client(
      block: suspend io.ktor.client.HttpClient.(baseUrl: URLBuilder) -> Unit
    ) {
      block(this.ktorHttpClient, URLBuilder(this.options.baseUrl))
    }
  }
}
