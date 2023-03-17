@file:Suppress("UNCHECKED_CAST")

package com.trendyol.stove.testing.e2e.elasticsearch

import arrow.core.*
import co.elastic.clients.elasticsearch.ElasticsearchClient
import co.elastic.clients.elasticsearch._types.Refresh
import co.elastic.clients.elasticsearch._types.query_dsl.Query
import co.elastic.clients.elasticsearch.core.DeleteRequest
import co.elastic.clients.elasticsearch.core.SearchRequest
import co.elastic.clients.json.jackson.JacksonJsonpMapper
import co.elastic.clients.transport.rest_client.RestClientTransport
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.trendyol.stove.functional.Try
import com.trendyol.stove.functional.recover
import com.trendyol.stove.testing.e2e.containers.ExposedCertificate
import com.trendyol.stove.testing.e2e.containers.NoCertificate
import com.trendyol.stove.testing.e2e.database.DocumentDatabaseSystem
import com.trendyol.stove.testing.e2e.system.TestSystem
import com.trendyol.stove.testing.e2e.system.abstractions.*
import javax.net.ssl.SSLContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.apache.http.HttpHost
import org.apache.http.auth.AuthScope
import org.apache.http.auth.UsernamePasswordCredentials
import org.apache.http.client.CredentialsProvider
import org.apache.http.impl.client.BasicCredentialsProvider
import org.apache.http.impl.nio.client.HttpAsyncClientBuilder
import org.elasticsearch.client.*
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import kotlin.jvm.optionals.getOrElse
import kotlin.reflect.KClass

class ElasticsearchSystem internal constructor(
    override val testSystem: TestSystem,
    private val context: ElasticsearchContext,
) : DocumentDatabaseSystem, RunAware, AfterRunAware, ExposesConfiguration {
    private lateinit var esClient: ElasticsearchClient
    private lateinit var exposedConfiguration: ElasticSearchExposedConfiguration
    private val logger: Logger = LoggerFactory.getLogger(javaClass)
    private val state: StateOfSystem<ElasticsearchSystem, ElasticSearchExposedConfiguration> =
        StateOfSystem(testSystem.options, ElasticsearchSystem::class, ElasticSearchExposedConfiguration::class)

    override suspend fun run() {
        exposedConfiguration = state.capture {
            context.container.start()
            ElasticSearchExposedConfiguration(
                context.container.host,
                context.container.firstMappedPort,
                context.options.containerOptions.password,
                determineCertificate()
            )
        }
    }

    private fun determineCertificate(): ExposedCertificate = when (context.options.containerOptions.disableSecurity) {
        true -> NoCertificate
        false -> ElasticsearchExposedCertificate(
            context.container.caCertAsBytes().getOrElse { ByteArray(0) },
            context.container.createSslContextFromCa()
        )
    }

    override suspend fun afterRun() {
        esClient = createEsClient(exposedConfiguration)
        if (!state.isSubsequentRun()) {
            context.options.migrationCollection.run(esClient)
        }
    }

    override suspend fun stop(): Unit = context.container.stop()

    override suspend fun <T : Any> shouldQuery(
        query: String,
        assertion: (List<T>) -> Unit,
        clazz: KClass<T>,
    ): ElasticsearchSystem =
        esClient.search(
            SearchRequest.of { req ->
                req.index(context.index).query { q -> q.withJson(query.reader()) }
            },
            clazz.java
        )
            .hits().hits()
            .mapNotNull { it.source() }
            .also(assertion)
            .let { this }

    fun <T : Any> shouldQuery(
        query: Query,
        assertion: (List<T>) -> Unit,
        clazz: KClass<T>,
    ): ElasticsearchSystem =
        esClient.search(
            SearchRequest.of { q -> q.query(query) },
            clazz.java
        )
            .hits().hits()
            .mapNotNull { it.source() }
            .also(assertion)
            .let { this }

    override suspend fun <T : Any> shouldGet(
        key: String,
        assertion: (T) -> Unit,
        clazz: KClass<T>,
    ): ElasticsearchSystem = esClient.get({ req -> req.index(context.index).id(key).refresh(true) }, clazz.java)
        .source().toOption()
        .map(assertion)
        .orElse { throw AssertionError("Resource with key ($key) is not found") }
        .let { this }

    override suspend fun shouldDelete(key: String): ElasticsearchSystem = esClient
        .delete(DeleteRequest.of { req -> req.index(context.index).id(key).refresh(Refresh.WaitFor) })
        .let { this }

    override suspend fun <T : Any> save(
        collection: String,
        id: String,
        instance: T,
    ): ElasticsearchSystem = esClient.index { req ->
        req.index(collection)
            .id(id)
            .document(instance)
            .refresh(Refresh.WaitFor)
    }.let { this }

    suspend fun <T : Any> save(
        id: String,
        instance: T,
    ): ElasticsearchSystem = save(context.index, id, instance)

    override fun close(): Unit = runBlocking(context = Dispatchers.IO) {
        Try {
            esClient._transport().close()
            executeWithReuseCheck { stop() }
        }.recover { logger.warn("got an error while stopping elasticsearch: ${it.message}") }
    }

    override fun configuration(): List<String> {
        return context.options.configureExposedConfiguration(exposedConfiguration) +
            listOf(
                "elasticsearch.host=${exposedConfiguration.host}",
                "elasticsearch.port=${exposedConfiguration.port}"
            )
    }

    private fun createEsClient(
        exposedConfiguration: ElasticSearchExposedConfiguration,
    ): ElasticsearchClient =
        context.options.clientConfigurer.restClientOverrideFn
            .getOrElse { { cfg -> restClient(cfg) } }
            .let { RestClientTransport(it(exposedConfiguration), JacksonJsonpMapper(jacksonObjectMapper())) }
            .let { ElasticsearchClient(it) }

    private fun restClient(cfg: ElasticSearchExposedConfiguration): RestClient =
        when (context.options.containerOptions.disableSecurity) {
            true -> RestClient.builder(HttpHost(exposedConfiguration.host, exposedConfiguration.port)).apply {
                setHttpClientConfigCallback { http -> http.also(context.options.clientConfigurer.httpClientBuilder) }
            }.build()

            false -> secureRestClient(cfg, context.container.createSslContextFromCa())
        }

    private fun secureRestClient(
        exposedConfiguration: ElasticSearchExposedConfiguration,
        sslContext: SSLContext,
    ): RestClient {
        val credentialsProvider: CredentialsProvider = BasicCredentialsProvider()
        credentialsProvider.setCredentials(
            AuthScope.ANY,
            UsernamePasswordCredentials("elastic", exposedConfiguration.password)
        )
        val builder: RestClientBuilder = RestClient
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
         * Executes the given [query] and returns a list for [assertion]
         * Caller-side needs to assert based on the list
         */
        inline fun <reified T : Any> ElasticsearchSystem.shouldQuery(
            query: Query,
            noinline assertion: (List<T>) -> Unit,
        ): ElasticsearchSystem = this.shouldQuery(query, assertion, T::class)
    }
}
