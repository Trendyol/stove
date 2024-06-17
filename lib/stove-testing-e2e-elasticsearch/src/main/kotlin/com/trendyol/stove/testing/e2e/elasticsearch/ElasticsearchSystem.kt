package com.trendyol.stove.testing.e2e.elasticsearch

import arrow.core.*
import co.elastic.clients.elasticsearch.ElasticsearchClient
import co.elastic.clients.elasticsearch._types.Refresh
import co.elastic.clients.elasticsearch._types.query_dsl.Query
import co.elastic.clients.elasticsearch.core.*
import co.elastic.clients.json.jackson.JacksonJsonpMapper
import co.elastic.clients.transport.rest_client.RestClientTransport
import com.trendyol.stove.functional.*
import com.trendyol.stove.testing.e2e.system.TestSystem
import com.trendyol.stove.testing.e2e.system.abstractions.*
import kotlinx.coroutines.*
import org.apache.http.HttpHost
import org.apache.http.auth.*
import org.apache.http.client.CredentialsProvider
import org.apache.http.impl.client.BasicCredentialsProvider
import org.apache.http.impl.nio.client.HttpAsyncClientBuilder
import org.elasticsearch.client.*
import org.slf4j.*
import javax.net.ssl.SSLContext
import kotlin.jvm.optionals.getOrElse

@ElasticDsl
class ElasticsearchSystem internal constructor(
  override val testSystem: TestSystem,
  val context: ElasticsearchContext
) : PluggedSystem, RunAware, AfterRunAware, ExposesConfiguration {
  @PublishedApi
  internal lateinit var esClient: ElasticsearchClient

  private lateinit var exposedConfiguration: ElasticSearchExposedConfiguration
  private val logger: Logger = LoggerFactory.getLogger(javaClass)
  private val state: StateStorage<ElasticSearchExposedConfiguration> =
    testSystem.options.createStateStorage<ElasticSearchExposedConfiguration, ElasticsearchSystem>()

  override suspend fun run() {
    exposedConfiguration = state.capture {
      context.container.start()
      ElasticSearchExposedConfiguration(
        context.container.host,
        context.container.firstMappedPort,
        context.options.container.password,
        determineCertificate()
      )
    }
  }

  private fun determineCertificate(): Option<ElasticsearchExposedCertificate> =
    when (context.options.container.disableSecurity) {
      true -> None
      false ->
        ElasticsearchExposedCertificate(
          context.container.caCertAsBytes().getOrElse { ByteArray(0) }
        ).apply { sslContext = context.container.createSslContextFromCa() }.some()
    }

  override suspend fun afterRun() {
    esClient = createEsClient(exposedConfiguration)
    if (!state.isSubsequentRun() || testSystem.options.runMigrationsAlways) {
      context.options.migrationCollection.run(esClient)
    }
  }

  override suspend fun stop(): Unit = context.container.stop()

  @ElasticDsl
  inline fun <reified T : Any> shouldQuery(
    query: String,
    assertion: (List<T>) -> Unit
  ): ElasticsearchSystem =
    esClient.search(
      SearchRequest.of { req ->
        req.index(context.index).query { q -> q.withJson(query.reader()) }
      },
      T::class.java
    ).hits().hits()
      .mapNotNull { it.source() }
      .also(assertion)
      .let { this }

  @ElasticDsl
  inline fun <reified T : Any> shouldQuery(
    query: Query,
    assertion: (List<T>) -> Unit
  ): ElasticsearchSystem =
    esClient.search(
      SearchRequest.of { q -> q.query(query) },
      T::class.java
    ).hits().hits()
      .mapNotNull { it.source() }
      .also(assertion)
      .let { this }

  @ElasticDsl
  inline fun <reified T : Any> shouldGet(
    index: String = context.index,
    key: String,
    assertion: (T) -> Unit
  ): ElasticsearchSystem {
    require(index.isNotBlank()) { "Index cannot be blank" }
    return esClient
      .get({ req -> req.index(index).id(key).refresh(true) }, T::class.java)
      .source().toOption()
      .map(assertion)
      .getOrElse { throw AssertionError("Resource with key ($key) is not found") }
      .let { this }
  }

  @ElasticDsl
  fun shouldNotExist(
    key: String,
    onIndex: String = context.index
  ): ElasticsearchSystem {
    val exists = esClient.exists { req -> req.index(onIndex).id(key) }
    if (exists.value()) {
      throw AssertionError("The document with the given id($key) was not expected, but found!")
    }
    return this
  }

  @ElasticDsl
  fun shouldDelete(
    key: String,
    fromIndex: String = context.index
  ): ElasticsearchSystem =
    esClient
      .delete(DeleteRequest.of { req -> req.index(fromIndex).id(key).refresh(Refresh.WaitFor) })
      .let { this }

  @ElasticDsl
  fun <T : Any> save(
    id: String,
    instance: T,
    toIndex: String = context.index
  ): ElasticsearchSystem =
    esClient.index { req ->
      req.index(toIndex)
        .id(id)
        .document(instance)
        .refresh(Refresh.WaitFor)
    }.let { this }

  /**
   * Pauses the container. Use with care, as it will pause the container which might affect other tests.
   * @return KafkaSystem
   */
  @ElasticDsl
  fun pause(): ElasticsearchSystem = context.container.pause().let { this }

  /**
   * Unpauses the container. Use with care, as it will unpause the container which might affect other tests.
   * @return KafkaSystem
   */
  @ElasticDsl
  fun unpause(): ElasticsearchSystem = context.container.unpause().let { this }

  override fun close(): Unit =
    runBlocking(context = Dispatchers.IO) {
      Try {
        esClient._transport().close()
        executeWithReuseCheck { stop() }
      }.recover { logger.warn("got an error while stopping elasticsearch: ${it.message}") }
    }

  override fun configuration(): List<String> =
    context.options.configureExposedConfiguration(exposedConfiguration) +
      listOf(
        "elasticsearch.host=${exposedConfiguration.host}",
        "elasticsearch.port=${exposedConfiguration.port}"
      )

  private fun createEsClient(exposedConfiguration: ElasticSearchExposedConfiguration): ElasticsearchClient =
    context.options.clientConfigurer.restClientOverrideFn
      .getOrElse { { cfg -> restClient(cfg) } }
      .let { RestClientTransport(it(exposedConfiguration), JacksonJsonpMapper(context.options.objectMapper)) }
      .let { ElasticsearchClient(it) }

  private fun restClient(cfg: ElasticSearchExposedConfiguration): RestClient =
    when (context.options.container.disableSecurity) {
      true ->
        RestClient.builder(HttpHost(exposedConfiguration.host, exposedConfiguration.port)).apply {
          setHttpClientConfigCallback { http -> http.also(context.options.clientConfigurer.httpClientBuilder) }
        }.build()

      false -> secureRestClient(cfg, context.container.createSslContextFromCa())
    }

  private fun secureRestClient(
    exposedConfiguration: ElasticSearchExposedConfiguration,
    sslContext: SSLContext
  ): RestClient {
    val credentialsProvider: CredentialsProvider = BasicCredentialsProvider()
    credentialsProvider.setCredentials(
      AuthScope.ANY,
      UsernamePasswordCredentials("elastic", exposedConfiguration.password)
    )
    val builder: RestClientBuilder =
      RestClient
        .builder(HttpHost(exposedConfiguration.host, exposedConfiguration.port, "https"))

    return builder.setHttpClientConfigCallback { clientBuilder: HttpAsyncClientBuilder ->
      clientBuilder.setSSLContext(sslContext)
      clientBuilder.setDefaultCredentialsProvider(credentialsProvider)
      context.options.clientConfigurer.httpClientBuilder(clientBuilder)
      clientBuilder
    }.build()
  }

  companion object {
    /**
     * Exposes the [ElasticsearchClient] for the given [ElasticsearchSystem]
     * This is useful for custom queries
     */
    @Suppress("unused")
    @ElasticDsl
    fun ElasticsearchSystem.client(): ElasticsearchClient = this.esClient
  }
}
