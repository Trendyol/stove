@file:Suppress("unused")

package com.trendyol.stove.testing.e2e.elasticsearch

import arrow.core.getOrElse
import com.fasterxml.jackson.databind.*
import com.trendyol.stove.functional.*
import com.trendyol.stove.testing.e2e.system.TestSystem
import com.trendyol.stove.testing.e2e.system.abstractions.*
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.jackson.*
import kotlinx.coroutines.runBlocking
import org.slf4j.*
import kotlin.time.Duration.Companion.minutes
import kotlin.time.toJavaDuration

@Suppress("TooManyFunctions")
@ElasticDsl
class ElasticsearchSystem internal constructor(
  override val testSystem: TestSystem,
  private val context: ElasticsearchContext
) : PluggedSystem,
  RunAware,
  AfterRunAware,
  ExposesConfiguration {
  @PublishedApi
  internal lateinit var httpClient: HttpClient

  @PublishedApi
  internal lateinit var baseUrl: String

  @PublishedApi
  internal val objectMapper: ObjectMapper = context.options.objectMapper

  private lateinit var exposedConfiguration: ElasticSearchExposedConfiguration
  private val logger: Logger = LoggerFactory.getLogger(javaClass)
  private val state: StateStorage<ElasticSearchExposedConfiguration> =
    testSystem.options.createStateStorage<ElasticSearchExposedConfiguration, ElasticsearchSystem>()

  override suspend fun run() {
    exposedConfiguration = obtainExposedConfiguration()
  }

  override suspend fun afterRun() {
    baseUrl = buildBaseUrl(exposedConfiguration)
    httpClient = createHttpClient(exposedConfiguration)
    runMigrationsIfNeeded()
  }

  override suspend fun stop(): Unit = whenContainer { it.stop() }

  override fun close(): Unit = runBlocking {
    Try {
      context.options.cleanup(this@ElasticsearchSystem)
      httpClient.close()
      executeWithReuseCheck { stop() }
    }.recover { logger.warn("got an error while stopping elasticsearch: ${it.message}") }
  }

  override fun configuration(): List<String> = context.options.configureExposedConfiguration(exposedConfiguration)

  @ElasticDsl
  suspend inline fun <reified T : Any> shouldQuery(
    query: String,
    index: String,
    assertion: (List<T>) -> Unit
  ): ElasticsearchSystem {
    requireValidIndex(index)
    require(query.isNotBlank()) { "Query cannot be blank" }

    val response = httpClient.post(Endpoint.search(index)) {
      contentType(ContentType.Application.Json)
      setBody(query)
    }

    response.ensureSuccess("Search query")

    val responseBody = response.body<JsonNode>()
    val results = responseBody.extractSearchHits(T::class.java)
    assertion(results)
    return this
  }

  @ElasticDsl
  suspend inline fun <reified T : Any> shouldGet(
    index: String,
    key: String,
    assertion: (T) -> Unit
  ): ElasticsearchSystem {
    requireValidIndex(index)
    requireValidKey(key)

    val response = httpClient.get(Endpoint.document(index, key)) {
      parameter(QueryParam.REFRESH, true)
    }

    if (response.status == HttpStatusCode.NotFound) {
      throw AssertionError("Resource with key ($key) is not found")
    }

    response.ensureSuccess("Get request")

    val responseBody = response.body<JsonNode>()
    val result = responseBody.extractSource(T::class.java)
      ?: throw AssertionError("Resource with key ($key) is not found")

    assertion(result)
    return this
  }

  @ElasticDsl
  suspend fun shouldNotExist(
    key: String,
    index: String
  ): ElasticsearchSystem {
    requireValidIndex(index)
    requireValidKey(key)

    val response = httpClient.head(Endpoint.document(index, key))

    if (response.status.isSuccess()) {
      throw AssertionError("The document with the given id($key) was not expected, but found!")
    }

    return this
  }

  @ElasticDsl
  suspend fun shouldDelete(
    key: String,
    index: String
  ): ElasticsearchSystem {
    requireValidIndex(index)
    requireValidKey(key)

    val response = httpClient.delete(Endpoint.document(index, key)) {
      parameter(QueryParam.REFRESH, QueryParam.WAIT_FOR)
    }

    response.ensureSuccessOrNotFound("Delete request")
    return this
  }

  @ElasticDsl
  suspend inline fun <reified T : Any> save(
    id: String,
    instance: T,
    index: String
  ): ElasticsearchSystem {
    requireValidIndex(index)
    require(id.isNotBlank()) { "Id cannot be blank" }

    val response = httpClient.put(Endpoint.document(index, id)) {
      contentType(ContentType.Application.Json)
      parameter(QueryParam.REFRESH, QueryParam.WAIT_FOR)
      setBody(instance)
    }

    response.ensureSuccess("Save request")
    return this
  }

  /**
   * Creates an index with the given name.
   * @param index The name of the index to create
   * @param settings Optional JSON settings for the index
   * @return ElasticsearchSystem
   */
  @ElasticDsl
  suspend fun createIndex(
    index: String,
    settings: String? = null
  ): ElasticsearchSystem {
    requireValidIndex(index)

    val response = httpClient.put(Endpoint.index(index)) {
      contentType(ContentType.Application.Json)
      settings?.let { setBody(it) }
    }

    check(response.status.isSuccess() || response.status.value == HttpStatusCode.BadRequest.value) {
      "Create index request failed with status ${response.status}: ${response.bodyAsText()}"
    }

    return this
  }

  /**
   * Deletes an index with the given name.
   * @param index The name of the index to delete
   * @return ElasticsearchSystem
   */
  @ElasticDsl
  suspend fun deleteIndex(index: String): ElasticsearchSystem {
    requireValidIndex(index)

    val response = httpClient.delete(Endpoint.index(index))
    response.ensureSuccessOrNotFound("Delete index request")
    return this
  }

  /**
   * Checks if an index exists.
   * @param index The name of the index to check
   * @return true if the index exists, false otherwise
   */
  @ElasticDsl
  suspend fun indexExists(index: String): Boolean {
    requireValidIndex(index)
    val response = httpClient.head(Endpoint.index(index))
    return response.status.isSuccess()
  }

  /**
   * Refreshes an index to make all operations performed since the last refresh available for search.
   * @param index The name of the index to refresh
   * @return ElasticsearchSystem
   */
  @ElasticDsl
  suspend fun refreshIndex(index: String): ElasticsearchSystem {
    requireValidIndex(index)

    val response = httpClient.post(Endpoint.refresh(index))
    response.ensureSuccess("Refresh index request")
    return this
  }

  /**
   * Pauses the container. Use with care, as it will pause the container which might affect other tests.
   * This operation is not supported when using a provided instance.
   * @return ElasticsearchSystem
   */
  @Suppress("unused")
  @ElasticDsl
  fun pause(): ElasticsearchSystem = withContainerOrWarn("pause") { it.pause() }

  /**
   * Unpauses the container. Use with care, as it will unpause the container which might affect other tests.
   * This operation is not supported when using a provided instance.
   * @return ElasticsearchSystem
   */
  @Suppress("unused")
  @ElasticDsl
  fun unpause(): ElasticsearchSystem = withContainerOrWarn("unpause") { it.unpause() }

  // region Private helpers

  @PublishedApi
  internal fun requireValidIndex(index: String) {
    require(index.isNotBlank()) { "Index cannot be blank" }
  }

  @PublishedApi
  internal fun requireValidKey(key: String) {
    require(key.isNotBlank()) { "Key cannot be blank" }
  }

  @PublishedApi
  internal suspend fun HttpResponse.ensureSuccess(operation: String) {
    check(status.isSuccess()) {
      "$operation failed with status $status: ${bodyAsText()}"
    }
  }

  @PublishedApi
  internal suspend fun HttpResponse.ensureSuccessOrNotFound(operation: String) {
    check(status.isSuccess() || status == HttpStatusCode.NotFound) {
      "$operation failed with status $status: ${bodyAsText()}"
    }
  }

  @PublishedApi
  internal fun <T : Any> JsonNode.extractSearchHits(clazz: Class<T>): List<T> =
    this[ResponseField.HITS][ResponseField.HITS]
      .mapNotNull { hit -> hit[ResponseField.SOURCE]?.let { objectMapper.treeToValue(it, clazz) } }

  @PublishedApi
  internal fun <T : Any> JsonNode.extractSource(clazz: Class<T>): T? =
    this[ResponseField.SOURCE]?.let { objectMapper.treeToValue(it, clazz) }

  private suspend fun obtainExposedConfiguration(): ElasticSearchExposedConfiguration =
    when {
      context.options is ProvidedElasticsearchSystemOptions -> context.options.config
      context.runtime is StoveElasticSearchContainer -> startElasticsearchContainer(context.runtime)
      else -> throw UnsupportedOperationException("Unsupported runtime type: ${context.runtime::class}")
    }

  private suspend fun startElasticsearchContainer(container: StoveElasticSearchContainer): ElasticSearchExposedConfiguration =
    state.capture {
      container.start()
      ElasticSearchExposedConfiguration(
        host = container.host,
        port = container.firstMappedPort,
        password = context.options.container.password,
        isSecure = !context.options.container.disableSecurity
      )
    }

  private suspend fun runMigrationsIfNeeded() {
    if (shouldRunMigrations()) {
      context.options.migrationCollection.run(this)
    }
  }

  private fun shouldRunMigrations(): Boolean = when {
    context.options is ProvidedElasticsearchSystemOptions -> context.options.runMigrations
    context.runtime is StoveElasticSearchContainer -> !state.isSubsequentRun() || testSystem.options.runMigrationsAlways
    else -> throw UnsupportedOperationException("Unsupported runtime type: ${context.runtime::class}")
  }

  private fun buildBaseUrl(cfg: ElasticSearchExposedConfiguration): String {
    val scheme = if (cfg.isSecure) Scheme.HTTPS else Scheme.HTTP
    return "$scheme://${cfg.host}:${cfg.port}"
  }

  private fun createHttpClient(cfg: ElasticSearchExposedConfiguration): HttpClient =
    context.options.httpClientConfigurer
      .getOrElse { { defaultHttpClient(cfg) } }
      .invoke(cfg)

  private fun defaultHttpClient(cfg: ElasticSearchExposedConfiguration): HttpClient =
    HttpClient(OkHttp) {
      engine {
        config {
          followRedirects(true)
          followSslRedirects(true)
          connectTimeout(DEFAULT_TIMEOUT.toJavaDuration())
          readTimeout(DEFAULT_TIMEOUT.toJavaDuration())
          callTimeout(DEFAULT_TIMEOUT.toJavaDuration())
          writeTimeout(DEFAULT_TIMEOUT.toJavaDuration())

          if (cfg.isSecure) {
            configureTrustAllCertificates()
          }
        }
      }

      install(ContentNegotiation) {
        register(ContentType.Application.Json, JacksonConverter(objectMapper))
      }

      defaultRequest {
        url(buildBaseUrl(cfg))
        if (cfg.isSecure && cfg.password.isNotBlank()) {
          header(HttpHeaders.Authorization, cfg.basicAuthHeader())
        }
      }
    }

  private fun okhttp3.OkHttpClient.Builder.configureTrustAllCertificates() {
    val trustAllCerts = arrayOf<javax.net.ssl.TrustManager>(
      object : javax.net.ssl.X509TrustManager {
        override fun checkClientTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String) = Unit

        override fun checkServerTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String) = Unit

        override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = arrayOf()
      }
    )
    val sslContext = javax.net.ssl.SSLContext
      .getInstance(TLS_PROTOCOL)
    sslContext.init(null, trustAllCerts, java.security.SecureRandom())
    sslSocketFactory(sslContext.socketFactory, trustAllCerts[0] as javax.net.ssl.X509TrustManager)
    hostnameVerifier { _, _ -> true }
  }

  private fun ElasticSearchExposedConfiguration.basicAuthHeader(): String {
    val credentials = java.util.Base64
      .getEncoder()
      .encodeToString("$DEFAULT_USERNAME:$password".toByteArray())
    return "Basic $credentials"
  }

  private inline fun withContainerOrWarn(
    operation: String,
    action: (StoveElasticSearchContainer) -> Unit
  ): ElasticsearchSystem = when (val runtime = context.runtime) {
    is StoveElasticSearchContainer -> {
      action(runtime)
      this
    }

    is ProvidedRuntime -> {
      logger.warn("$operation() is not supported when using a provided instance")
      this
    }

    else -> {
      throw UnsupportedOperationException("Unsupported runtime type: ${runtime::class}")
    }
  }

  private inline fun whenContainer(action: (StoveElasticSearchContainer) -> Unit) {
    if (context.runtime is StoveElasticSearchContainer) {
      action(context.runtime)
    }
  }

  // endregion

  companion object {
    private val DEFAULT_TIMEOUT = 5.minutes
    private const val DEFAULT_USERNAME = "elastic"
    private const val TLS_PROTOCOL = "TLS"

    /**
     * Exposes the [HttpClient] for the given [ElasticsearchSystem].
     * This is useful for custom operations.
     */
    @Suppress("unused")
    @ElasticDsl
    fun ElasticsearchSystem.client(): HttpClient = this.httpClient

    /**
     * Returns the base URL for the Elasticsearch instance.
     */
    @Suppress("unused")
    @ElasticDsl
    fun ElasticsearchSystem.baseUrl(): String = this.baseUrl
  }

  /**
   * Elasticsearch API endpoint builders (relative paths).
   */
  @PublishedApi
  internal object Endpoint {
    private const val DOC = "_doc"
    private const val SEARCH = "_search"
    private const val REFRESH = "_refresh"

    fun index(index: String): String = "/$index"

    fun document(index: String, id: String): String = "/$index/$DOC/$id"

    fun search(index: String): String = "/$index/$SEARCH"

    fun refresh(index: String): String = "/$index/$REFRESH"
  }

  /**
   * Elasticsearch response field names.
   */
  @PublishedApi
  internal object ResponseField {
    const val HITS = "hits"
    const val SOURCE = "_source"
  }

  /**
   * Query parameter names and values.
   */
  @PublishedApi
  internal object QueryParam {
    const val REFRESH = "refresh"
    const val WAIT_FOR = "wait_for"
  }

  /**
   * URL scheme constants.
   */
  private object Scheme {
    const val HTTP = "http"
    const val HTTPS = "https"
  }
}
