@file:Suppress("MemberVisibilityCanBePrivate")

package com.trendyol.stove.testing.e2e.http

import arrow.core.*
import com.fasterxml.jackson.databind.ObjectMapper
import com.trendyol.stove.testing.e2e.serialization.StoveObjectMapper
import com.trendyol.stove.testing.e2e.system.*
import com.trendyol.stove.testing.e2e.system.abstractions.*
import com.trendyol.stove.testing.e2e.system.annotations.StoveDsl
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.logging.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.http.*
import io.ktor.serialization.jackson.*
import io.ktor.util.*
import org.slf4j.LoggerFactory
import java.net.http.HttpClient
import kotlin.time.*
import kotlin.time.Duration.Companion.seconds

private val httpSystemLogger = LoggerFactory.getLogger(HttpSystem::class.java)

@HttpDsl
data class HttpClientSystemOptions(
  val baseUrl: String,
  val objectMapper: ObjectMapper = StoveObjectMapper.Default,
  val timeout: Duration = 30.seconds,
  val createClient: () -> io.ktor.client.HttpClient = { jsonHttpClient(timeout, objectMapper) }
) : SystemOptions {
  companion object {
    internal fun jsonHttpClient(
      timeout: Duration,
      objectMapper: ObjectMapper
    ): io.ktor.client.HttpClient = HttpClient(OkHttp) {
      engine {
        config {
          followRedirects(true)
          followSslRedirects(true)
          connectTimeout(timeout.toJavaDuration())
          readTimeout(timeout.toJavaDuration())
          callTimeout(timeout.toJavaDuration())
          writeTimeout(timeout.toJavaDuration())
        }
      }
      install(Logging) {
        logger = object : Logger {
          override fun log(message: String) {
            httpSystemLogger.info(message)
          }
        }
      }

      install(ContentNegotiation) {
        register(ContentType.Application.Json, JacksonConverter(objectMapper))
        register(ContentType.Application.ProblemJson, JacksonConverter(objectMapper))
        register(ContentType.parse("application/x-ndjson"), JacksonConverter(objectMapper))
      }

      defaultRequest {
        header(HttpHeaders.ContentType, ContentType.Application.Json)
        header(HttpHeaders.Accept, ContentType.Application.Json)
      }
    }
  }
}

internal fun TestSystem.withHttpClient(options: HttpClientSystemOptions): TestSystem {
  this.getOrRegister(HttpSystem(this, options))
  return this
}

internal fun TestSystem.http(): HttpSystem = getOrNone<HttpSystem>().getOrElse {
  throw SystemNotRegisteredException(HttpSystem::class)
}

@StoveDsl
fun WithDsl.httpClient(configure: @StoveDsl () -> HttpClientSystemOptions): TestSystem =
  this.testSystem.withHttpClient(configure())

@StoveDsl
suspend fun ValidationDsl.http(
  validation: @HttpDsl suspend HttpSystem.() -> Unit
): Unit = validation(this.testSystem.http())

@HttpDsl
class HttpSystem(
  override val testSystem: TestSystem,
  @PublishedApi internal val options: HttpClientSystemOptions
) : PluggedSystem {
  @PublishedApi
  internal val ktorHttpClient: io.ktor.client.HttpClient = options.createClient()

  @HttpDsl
  suspend fun getResponse(
    uri: String,
    queryParams: Map<String, String> = mapOf(),
    headers: Map<String, String> = mapOf(),
    token: Option<String> = None,
    expect: suspend (StoveHttpResponse) -> Unit
  ): HttpSystem = get(uri, headers, queryParams, token).also {
    expect(StoveHttpResponse.Bodiless(it.status.value, it.headers.toMap()))
  }.let { this }

  @HttpDsl
  suspend inline fun <reified T : Any> getResponse(
    uri: String,
    queryParams: Map<String, String> = mapOf(),
    headers: Map<String, String> = mapOf(),
    token: Option<String> = None,
    expect: (StoveHttpResponse.WithBody<T>) -> Unit
  ): HttpSystem = get(uri, headers, queryParams, token).also {
    expect(StoveHttpResponse.WithBody(it.status.value, it.headers.toMap()) { it.body() })
  }.let { this }

  @HttpDsl
  suspend inline fun <reified TExpected : Any> get(
    uri: String,
    queryParams: Map<String, String> = mapOf(),
    headers: Map<String, String> = mapOf(),
    token: Option<String> = None,
    expect: (TExpected) -> Unit
  ): HttpSystem = get(uri, headers, queryParams, token).also {
    check(it.status.isSuccess()) { "Expected a successful response, but got ${it.status}" }
    expect(it.body())
  }.let { this }

  @HttpDsl
  suspend inline fun <reified TExpected : Any> getMany(
    uri: String,
    queryParams: Map<String, String> = mapOf(),
    headers: Map<String, String> = mapOf(),
    token: Option<String> = None,
    expect: (List<TExpected>) -> Unit
  ): HttpSystem = get(uri, headers, queryParams, token).also {
    check(it.status.isSuccess()) { "Expected a successful response, but got ${it.status}" }
    expect(it.body())
  }.let { this }

  @HttpDsl
  suspend fun postAndExpectBodilessResponse(
    uri: String,
    body: Option<Any>,
    token: Option<String> = None,
    headers: Map<String, String> = mapOf(),
    expect: suspend (StoveHttpResponse) -> Unit
  ): HttpSystem = ktorHttpClient.post(relative(uri)) {
    headers.forEach { (key, value) -> header(key, value) }
    token.map { header(HeaderConstants.AUTHORIZATION, HeaderConstants.bearer(it)) }
    body.map { setBody(it) }
  }.also { expect(StoveHttpResponse.Bodiless(it.status.value, it.headers.toMap())) }.let { this }

  @HttpDsl
  suspend inline fun <reified TExpected : Any> postAndExpectJson(
    uri: String,
    body: Option<Any> = None,
    headers: Map<String, String> = mapOf(),
    token: Option<String> = None,
    expect: (actual: TExpected) -> Unit
  ): HttpSystem = ktorHttpClient.post(relative(uri)) {
    headers.forEach { (key, value) -> header(key, value) }
    token.map { header(HeaderConstants.AUTHORIZATION, HeaderConstants.bearer(it)) }
    body.map { setBody(it) }
  }.also {
    check(it.status.isSuccess()) { "Expected a successful response, but got ${it.status}" }
    expect(it.body())
  }.let { this }

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
  ): HttpSystem = ktorHttpClient.post(relative(uri)) {
    headers.forEach { (key, value) -> header(key, value) }
    token.map { header(HeaderConstants.AUTHORIZATION, HeaderConstants.bearer(it)) }
    body.map { setBody(it) }
  }.also { expect(StoveHttpResponse.WithBody(it.status.value, it.headers.toMap()) { it.body() }) }.let { this }

  @HttpDsl
  suspend fun putAndExpectBodilessResponse(
    uri: String,
    body: Option<Any>,
    token: Option<String> = None,
    headers: Map<String, String> = mapOf(),
    expect: suspend (StoveHttpResponse) -> Unit
  ): HttpSystem = ktorHttpClient.put(relative(uri)) {
    headers.forEach { (key, value) -> header(key, value) }
    token.map { header(HeaderConstants.AUTHORIZATION, HeaderConstants.bearer(it)) }
    body.map { setBody(it) }
  }.also { expect(StoveHttpResponse.Bodiless(it.status.value, it.headers.toMap())) }
    .let { this }

  @HttpDsl
  suspend inline fun <reified TExpected : Any> putAndExpectJson(
    uri: String,
    body: Option<Any> = None,
    headers: Map<String, String> = mapOf(),
    token: Option<String> = None,
    expect: (actual: TExpected) -> Unit
  ): HttpSystem = ktorHttpClient.put(relative(uri)) {
    headers.forEach { (key, value) -> header(key, value) }
    token.map { header(HeaderConstants.AUTHORIZATION, HeaderConstants.bearer(it)) }
    body.map { setBody(it) }
  }.also {
    check(it.status.isSuccess()) { "Expected a successful response, but got ${it.status}" }
    expect(it.body())
  }.let { this }

  @HttpDsl
  suspend inline fun <reified TExpected : Any> putAndExpectBody(
    uri: String,
    body: Option<Any> = None,
    headers: Map<String, String> = mapOf(),
    token: Option<String> = None,
    expect: (actual: StoveHttpResponse.WithBody<TExpected>) -> Unit
  ): HttpSystem = ktorHttpClient.put(relative(uri)) {
    headers.forEach { (key, value) -> header(key, value) }
    token.map { header(HeaderConstants.AUTHORIZATION, HeaderConstants.bearer(it)) }
    body.map { setBody(it) }
  }.also { expect(StoveHttpResponse.WithBody(it.status.value, it.headers.toMap()) { it.body() }) }
    .let { this }

  @HttpDsl
  suspend fun patchAndExpectBodilessResponse(
    uri: String,
    body: Option<Any>,
    token: Option<String> = None,
    headers: Map<String, String> = mapOf(),
    expect: suspend (StoveHttpResponse) -> Unit
  ): HttpSystem = ktorHttpClient.patch(relative(uri)) {
    headers.forEach { (key, value) -> header(key, value) }
    token.map { header(HeaderConstants.AUTHORIZATION, HeaderConstants.bearer(it)) }
    body.map { setBody(it) }
  }.also { expect(StoveHttpResponse.Bodiless(it.status.value, it.headers.toMap())) }
    .let { this }

  @HttpDsl
  suspend inline fun <reified TExpected : Any> patchAndExpectJson(
    uri: String,
    body: Option<Any> = None,
    headers: Map<String, String> = mapOf(),
    token: Option<String> = None,
    expect: (actual: TExpected) -> Unit
  ): HttpSystem = ktorHttpClient.patch(relative(uri)) {
    headers.forEach { (key, value) -> header(key, value) }
    token.map { header(HeaderConstants.AUTHORIZATION, HeaderConstants.bearer(it)) }
    body.map { setBody(it) }
  }.also {
    check(it.status.isSuccess()) { "Expected a successful response, but got ${it.status}" }
    expect(it.body())
  }.let { this }

  @HttpDsl
  suspend inline fun <reified TExpected : Any> patchAndExpectBody(
    uri: String,
    body: Option<Any> = None,
    headers: Map<String, String> = mapOf(),
    token: Option<String> = None,
    expect: (actual: StoveHttpResponse.WithBody<TExpected>) -> Unit
  ): HttpSystem = ktorHttpClient.patch(relative(uri)) {
    headers.forEach { (key, value) -> header(key, value) }
    token.map { header(HeaderConstants.AUTHORIZATION, HeaderConstants.bearer(it)) }
    body.map { setBody(it) }
  }.also { expect(StoveHttpResponse.WithBody(it.status.value, it.headers.toMap()) { it.body() }) }
    .let { this }

  @HttpDsl
  suspend fun deleteAndExpectBodilessResponse(
    uri: String,
    token: Option<String> = None,
    headers: Map<String, String> = mapOf(),
    expect: suspend (StoveHttpResponse) -> Unit
  ): HttpSystem = ktorHttpClient.delete(relative(uri)) {
    headers.forEach { (key, value) -> header(key, value) }
    token.map { header(HeaderConstants.AUTHORIZATION, HeaderConstants.bearer(it)) }
  }.also { expect(StoveHttpResponse.Bodiless(it.status.value, it.headers.toMap())) }
    .let { this }

  @HttpDsl
  suspend inline fun <reified TExpected : Any> postMultipartAndExpectResponse(
    uri: String,
    body: List<StoveMultiPartContent>,
    headers: Map<String, String> = mapOf(),
    token: Option<String> = None,
    expect: (StoveHttpResponse.WithBody<TExpected>) -> Unit
  ): HttpSystem = ktorHttpClient.submitForm {
    url(relative(uri))
    headers.forEach { (key, value) -> header(key, value) }
    token.map { header(HeaderConstants.AUTHORIZATION, HeaderConstants.bearer(it)) }
    setBody(MultiPartFormDataContent(toFormData(body)))
  }.also { expect(StoveHttpResponse.WithBody(it.status.value, it.headers.toMap()) { it.body() }) }.let { this }

  @HttpDsl
  override fun then(): TestSystem = testSystem

  @PublishedApi
  internal suspend fun get(
    uri: String,
    headers: Map<String, String>,
    queryParams: Map<String, String>,
    token: Option<String>
  ) = ktorHttpClient.get(relative(uri)) {
    headers.forEach { (key, value) -> header(key, value) }
    queryParams.forEach { (key, value) -> parameter(key, value) }
    token.map { header(HeaderConstants.AUTHORIZATION, HeaderConstants.bearer(it)) }
  }

  @PublishedApi
  internal fun relative(uri: String): Url = URLBuilder(options.baseUrl)
    .apply { appendEncodedPathSegments(uri) }.build()

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
     * Exposes the [HttpClient] used by the [HttpSystem].
     */
    @Suppress("unused")
    @HttpDsl
    fun HttpSystem.client(): io.ktor.client.HttpClient = this.ktorHttpClient

    /**
     * Exposes the [HttpClient] used by the [HttpSystem].
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
