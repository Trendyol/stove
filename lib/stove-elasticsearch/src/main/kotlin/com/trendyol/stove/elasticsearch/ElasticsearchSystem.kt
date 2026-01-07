package com.trendyol.stove.elasticsearch

import arrow.core.*
import co.elastic.clients.elasticsearch.ElasticsearchClient
import co.elastic.clients.elasticsearch._types.Refresh
import co.elastic.clients.elasticsearch._types.query_dsl.Query
import co.elastic.clients.elasticsearch.core.*
import co.elastic.clients.transport.rest_client.RestClientTransport
import com.trendyol.stove.functional.*
import com.trendyol.stove.reporting.Reports
import com.trendyol.stove.system.Stove
import com.trendyol.stove.system.abstractions.*
import kotlinx.coroutines.runBlocking
import org.apache.http.HttpHost
import org.apache.http.auth.*
import org.apache.http.client.CredentialsProvider
import org.apache.http.impl.client.BasicCredentialsProvider
import org.apache.http.impl.nio.client.HttpAsyncClientBuilder
import org.elasticsearch.client.RestClient
import org.slf4j.*
import javax.net.ssl.SSLContext
import kotlin.jvm.optionals.getOrElse

/**
 * Elasticsearch search engine system for testing search operations.
 *
 * Provides a DSL for testing Elasticsearch operations:
 * - Document indexing and retrieval
 * - Search queries (JSON and Query builder)
 * - Index management
 * - Document deletion
 *
 * ## Indexing Documents
 *
 * ```kotlin
 * elasticsearch {
 *     // Save document with specific ID
 *     save("products", "product-123", Product(id = "123", name = "Widget"))
 *
 *     // Save with refresh (immediately searchable)
 *     save("products", "product-123", product, refresh = Refresh.True)
 * }
 * ```
 *
 * ## Retrieving Documents
 *
 * ```kotlin
 * elasticsearch {
 *     // Get by ID and assert
 *     shouldGet<Product>("products", "product-123") { product ->
 *         product.name shouldBe "Widget"
 *         product.price shouldBeGreaterThan 0.0
 *     }
 * }
 * ```
 *
 * ## Search Queries
 *
 * ```kotlin
 * elasticsearch {
 *     // Query with JSON syntax
 *     shouldQuery<Product>(
 *         query = """{ "match": { "name": "widget" } }""",
 *         index = "products"
 *     ) { products ->
 *         products.size shouldBeGreaterThan 0
 *     }
 *
 *     // Query with Elasticsearch Query builder
 *     shouldQuery<Product>(
 *         query = Query.of { q ->
 *             q.bool { b ->
 *                 b.must { m -> m.match { t -> t.field("category").query("electronics") } }
 *                 b.filter { f -> f.range { r -> r.field("price").gte(JsonData.of(100)) } }
 *             }
 *         },
 *         index = "products"
 *     ) { products ->
 *         products.all { it.category == "electronics" } shouldBe true
 *     }
 *
 *     // Complex search with aggregations (using client directly)
 *     client { es ->
 *         val response = es.search(SearchRequest.of { s ->
 *             s.index("products")
 *              .query(Query.of { q -> q.matchAll { } })
 *              .aggregations("by_category", Aggregation.of { a ->
 *                  a.terms { t -> t.field("category.keyword") }
 *              })
 *         }, Product::class.java)
 *
 *         response.aggregations()["by_category"]?.sterms()?.buckets()?.array()?.size shouldBeGreaterThan 0
 *     }
 * }
 * ```
 *
 * ## Deleting Documents
 *
 * ```kotlin
 * elasticsearch {
 *     shouldDelete("products", "product-123")
 * }
 * ```
 *
 * ## Test Workflow Example
 *
 * ```kotlin
 * test("should index product and make it searchable") {
 *     Stove.validate {
 *         val productId = UUID.randomUUID().toString()
 *
 *         // Create product via API
 *         http {
 *             postAndExpectBodilessResponse(
 *                 uri = "/products",
 *                 body = CreateProductRequest(id = productId, name = "Test Widget").some()
 *             ) { response ->
 *                 response.status shouldBe 201
 *             }
 *         }
 *
 *         // Verify in Elasticsearch (with eventual consistency wait)
 *         elasticsearch {
 *             eventually(10.seconds) {
 *                 shouldGet<Product>("products", productId) { product ->
 *                     product.name shouldBe "Test Widget"
 *                 }
 *             }
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
 *         elasticsearch {
 *             ElasticsearchSystemOptions(
 *                 configureExposedConfiguration = { cfg ->
 *                     listOf(
 *                         "elasticsearch.host=${cfg.host}",
 *                         "elasticsearch.port=${cfg.port}"
 *                     )
 *                 }
 *             ).migrations {
 *                 register<CreateProductIndexMigration>()
 *             }
 *         }
 *     }
 * ```
 *
 * @property stove The parent test system.
 * @see ElasticsearchSystemOptions
 * @see ElasticSearchExposedConfiguration
 */
@ElasticDsl
class ElasticsearchSystem internal constructor(
  override val stove: Stove,
  private val context: ElasticsearchContext
) : PluggedSystem,
  RunAware,
  AfterRunAware,
  ExposesConfiguration,
  Reports {
  @PublishedApi
  internal lateinit var esClient: ElasticsearchClient

  private lateinit var exposedConfiguration: ElasticSearchExposedConfiguration
  private val logger: Logger = LoggerFactory.getLogger(javaClass)
  private val state: StateStorage<ElasticSearchExposedConfiguration> =
    stove.options.createStateStorage<ElasticSearchExposedConfiguration, ElasticsearchSystem>()

  override suspend fun run() {
    exposedConfiguration = obtainExposedConfiguration()
  }

  override suspend fun afterRun() {
    esClient = createEsClient(exposedConfiguration)
    runMigrationsIfNeeded()
  }

  override suspend fun stop(): Unit = whenContainer { it.stop() }

  override fun close(): Unit = runBlocking {
    Try {
      context.options.cleanup(esClient)
      esClient._transport().close()
      executeWithReuseCheck { stop() }
    }.recover { logger.warn("got an error while stopping elasticsearch: ${it.message}") }
  }

  override fun configuration(): List<String> = context.options.configureExposedConfiguration(exposedConfiguration)

  @ElasticDsl
  suspend inline fun <reified T : Any> shouldQuery(
    query: String,
    index: String,
    crossinline assertion: (List<T>) -> Unit
  ): ElasticsearchSystem {
    require(index.isNotBlank()) { "Index cannot be blank" }
    require(query.isNotBlank()) { "Query cannot be blank" }

    recordAndExecute(
      action = "Search '$index'",
      input = arrow.core.Some(mapOf("index" to index, "query" to query))
    ) {
      val results = esClient
        .search(
          SearchRequest.of { req -> req.index(index).query { q -> q.withJson(query.reader()) } },
          T::class.java
        ).hits()
        .hits()
        .mapNotNull { it.source() }
      assertion(results)
      results
    }
    return this
  }

  @ElasticDsl
  suspend inline fun <reified T : Any> shouldQuery(
    query: Query,
    crossinline assertion: (List<T>) -> Unit
  ): ElasticsearchSystem {
    recordAndExecute(action = "Search with Query DSL") {
      val results = esClient
        .search(
          SearchRequest.of { q -> q.query(query) },
          T::class.java
        ).hits()
        .hits()
        .mapNotNull { it.source() }
      assertion(results)
      results
    }
    return this
  }

  @ElasticDsl
  suspend inline fun <reified T : Any> shouldGet(
    index: String,
    key: String,
    crossinline assertion: (T) -> Unit
  ): ElasticsearchSystem {
    require(index.isNotBlank()) { "Index cannot be blank" }
    require(key.isNotBlank()) { "Key cannot be blank" }

    recordAndExecute(
      action = "Get document",
      input = arrow.core.Some(mapOf("index" to index, "id" to key))
    ) {
      val document = esClient
        .get({ req -> req.index(index).id(key).refresh(true) }, T::class.java)
        .source()
        .toOption()
      document.map(assertion).getOrElse { throw AssertionError("Resource with key ($key) is not found") }
      document
    }
    return this
  }

  @ElasticDsl
  suspend fun shouldNotExist(
    key: String,
    index: String
  ): ElasticsearchSystem {
    require(index.isNotBlank()) { "Index cannot be blank" }
    require(key.isNotBlank()) { "Key cannot be blank" }

    recordAndExecute(
      action = "Document should not exist",
      input = arrow.core.Some(mapOf("index" to index, "id" to key)),
      expected = arrow.core.Some("Document not found")
    ) {
      val exists = esClient.exists { req -> req.index(index).id(key) }.value()
      if (exists) throw AssertionError("The document with the given id($key) was not expected, but found!")
    }
    return this
  }

  @ElasticDsl
  fun shouldDelete(
    key: String,
    index: String
  ): ElasticsearchSystem {
    require(index.isNotBlank()) { "Index cannot be blank" }
    require(key.isNotBlank()) { "Key cannot be blank" }

    executeAndRecord(
      action = "Delete document",
      metadata = mapOf("index" to index, "id" to key)
    ) {
      esClient.delete(DeleteRequest.of { req -> req.index(index).id(key).refresh(Refresh.WaitFor) })
    }
    return this
  }

  @ElasticDsl
  fun <T : Any> save(
    id: String,
    instance: T,
    index: String
  ): ElasticsearchSystem {
    require(index.isNotBlank()) { "Index cannot be blank" }
    require(id.isNotBlank()) { "Id cannot be blank" }

    executeAndRecord(
      action = "Index document",
      input = arrow.core.Some(instance),
      metadata = mapOf("index" to index, "id" to id)
    ) {
      esClient.index { req ->
        req
          .index(index)
          .id(id)
          .document(instance)
          .refresh(Refresh.WaitFor)
      }
    }
    return this
  }

  /**
   * Pauses the container. Use with care, as it will pause the container which might affect other tests.
   * This operation is not supported when using a provided instance.
   * @return ElasticsearchSystem
   */
  @Suppress("unused")
  @ElasticDsl
  fun pause(): ElasticsearchSystem {
    executeAndRecord(
      action = "Pause container",
      metadata = mapOf("operation" to "fault-injection")
    ) {
      withContainerOrWarn("pause") { it.pause() }
    }
    return this
  }

  /**
   * Unpauses the container. Use with care, as it will unpause the container which might affect other tests.
   * This operation is not supported when using a provided instance.
   * @return ElasticsearchSystem
   */
  @Suppress("unused")
  @ElasticDsl
  fun unpause(): ElasticsearchSystem {
    executeAndRecord(action = "Unpause container") {
      withContainerOrWarn("unpause") { it.unpause() }
    }
    return this
  }

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
        certificate = determineCertificate(container).getOrNull()
      )
    }

  private fun determineCertificate(container: StoveElasticSearchContainer): Option<ElasticsearchExposedCertificate> =
    when (context.options.container.disableSecurity) {
      true -> None

      false -> ElasticsearchExposedCertificate(
        container.caCertAsBytes().getOrElse { ByteArray(0) }
      ).apply { sslContext = container.createSslContextFromCa() }.some()
    }

  private suspend fun runMigrationsIfNeeded() {
    if (shouldRunMigrations()) {
      context.options.migrationCollection.run(esClient)
    }
  }

  private fun shouldRunMigrations(): Boolean = when {
    context.options is ProvidedElasticsearchSystemOptions -> context.options.runMigrations
    context.runtime is StoveElasticSearchContainer -> !state.isSubsequentRun() || stove.options.runMigrationsAlways
    else -> throw UnsupportedOperationException("Unsupported runtime type: ${context.runtime::class}")
  }

  private fun createEsClient(exposedConfiguration: ElasticSearchExposedConfiguration): ElasticsearchClient =
    context.options.clientConfigurer.restClientOverrideFn
      .getOrElse { { cfg -> restClient(cfg) } }
      .let { RestClientTransport(it(exposedConfiguration), context.options.jsonpMapper) }
      .let { ElasticsearchClient(it) }

  private fun restClient(cfg: ElasticSearchExposedConfiguration): RestClient =
    when (isSecurityDisabled(cfg)) {
      true -> createInsecureRestClient(cfg)
      false -> createSecureRestClient(cfg, obtainSslContext(cfg))
    }

  private fun isSecurityDisabled(cfg: ElasticSearchExposedConfiguration): Boolean = when {
    context.options is ProvidedElasticsearchSystemOptions -> cfg.certificate == null
    context.runtime is StoveElasticSearchContainer -> context.options.container.disableSecurity
    else -> throw UnsupportedOperationException("Unsupported runtime type: ${context.runtime::class}")
  }

  private fun obtainSslContext(cfg: ElasticSearchExposedConfiguration): SSLContext = when {
    context.options is ProvidedElasticsearchSystemOptions -> cfg.certificate?.sslContext ?: throw IllegalStateException(
      "SSL context is required for secure connections with provided instances. " +
        "Set the certificate.sslContext in ElasticSearchExposedConfiguration."
    )

    context.runtime is StoveElasticSearchContainer -> context.runtime.createSslContextFromCa()

    else -> throw UnsupportedOperationException("Unsupported runtime type: ${context.runtime::class}")
  }

  private fun createInsecureRestClient(cfg: ElasticSearchExposedConfiguration): RestClient =
    RestClient
      .builder(HttpHost(cfg.host, cfg.port))
      .apply { setHttpClientConfigCallback { http -> http.also(context.options.clientConfigurer.httpClientBuilder) } }
      .build()

  private fun createSecureRestClient(
    cfg: ElasticSearchExposedConfiguration,
    sslContext: SSLContext
  ): RestClient {
    val credentialsProvider: CredentialsProvider = BasicCredentialsProvider().apply {
      setCredentials(AuthScope.ANY, UsernamePasswordCredentials("elastic", cfg.password))
    }
    return RestClient
      .builder(HttpHost(cfg.host, cfg.port, "https"))
      .setHttpClientConfigCallback { clientBuilder: HttpAsyncClientBuilder ->
        clientBuilder.setSSLContext(sslContext)
        clientBuilder.setDefaultCredentialsProvider(credentialsProvider)
        context.options.clientConfigurer.httpClientBuilder(clientBuilder)
        clientBuilder
      }.build()
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

  companion object {
    /**
     * Exposes the [ElasticsearchClient] for the given [ElasticsearchSystem].
     * Use this for advanced Elasticsearch operations not covered by the DSL.
     */
    @Suppress("unused")
    @ElasticDsl
    fun ElasticsearchSystem.client(): ElasticsearchClient {
      recordSuccess(action = "Access underlying ElasticsearchClient")
      return this.esClient
    }
  }
}
