@file:Suppress("UNCHECKED_CAST")

package com.trendyol.stove.testing.e2e.elasticsearch

import arrow.core.getOrElse
import arrow.core.orElse
import arrow.core.toOption
import co.elastic.clients.elasticsearch.ElasticsearchClient
import co.elastic.clients.elasticsearch._types.Refresh
import co.elastic.clients.elasticsearch._types.query_dsl.Query
import co.elastic.clients.elasticsearch.core.DeleteRequest
import co.elastic.clients.elasticsearch.core.SearchRequest
import co.elastic.clients.json.jackson.JacksonJsonpMapper
import co.elastic.clients.transport.rest_client.RestClientTransport
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.trendyol.stove.testing.e2e.containers.withProvidedRegistry
import com.trendyol.stove.testing.e2e.database.DocumentDatabaseSystem
import com.trendyol.stove.testing.e2e.serialization.StoveObjectMapper
import com.trendyol.stove.testing.e2e.system.TestSystem
import com.trendyol.stove.testing.e2e.system.abstractions.*
import javax.net.ssl.SSLContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.apache.http.HttpHost
import org.apache.http.auth.AuthScope
import org.apache.http.auth.UsernamePasswordCredentials
import org.apache.http.client.CredentialsProvider
import org.apache.http.impl.client.BasicCredentialsProvider
import org.apache.http.impl.nio.client.HttpAsyncClientBuilder
import org.elasticsearch.client.*
import org.testcontainers.elasticsearch.ElasticsearchContainer
import kotlin.reflect.KClass

data class ElasticsearchSystemOptions(
    val defaultIndex: DefaultIndex,
    val containerOptions: ContainerOptions = ContainerOptions(),
    override val configureExposedConfiguration: (ElasticSearchExposedConfiguration) -> List<String> = { _ -> listOf() },
    val objectMapper: ObjectMapper = StoveObjectMapper.Default,
) : SystemOptions, ConfiguresExposedConfiguration<ElasticSearchExposedConfiguration> {

    internal val migrationCollection: MigrationCollection = MigrationCollection()

    /**
     * Helps for registering migrations before the tests run.
     * @see MigrationCollection
     * @see ElasticMigrator
     */
    fun migrations(migration: MigrationCollection.() -> Unit): ElasticsearchSystemOptions = migration(migrationCollection).let { this }
}

data class ElasticSearchExposedConfiguration(
    val host: String,
    val port: Int,
    val password: String,
) : ExposedConfiguration

data class ElasticsearchContext(
    val index: String,
    val container: ElasticsearchContainer,
    val options: ElasticsearchSystemOptions,
)

data class ContainerOptions(
    val registry: String = "docker.elastic.co/",
    val imageVersion: String = "8.6.1",
    val exposedPorts: List<Int> = listOf(9200),
    val password: String = "password",
)

/**
 * Integrates Elasticsearch with the TestSystem.
 *
 * Provides an [options] class to define [DefaultIndex] parameter to create an index as default index. You can configure it by changing the implementation of migrator.
 */
fun TestSystem.withElasticsearch(
    options: ElasticsearchSystemOptions,
): TestSystem {
    options.migrations {
        register<DefaultIndexMigrator> { options.defaultIndex.migrator }
    }

    return withProvidedRegistry(
        "elasticsearch/elasticsearch:${options.containerOptions.imageVersion}",
        options.containerOptions.registry
    ) { ElasticsearchContainer(it) }
        .apply {
            addExposedPorts(*options.containerOptions.exposedPorts.toIntArray())
            withPassword(options.containerOptions.password)
        }
        .let { getOrRegister(ElasticsearchSystem(this, ElasticsearchContext(options.defaultIndex.index, it, options))) }
        .let { this }
}

fun TestSystem.elasticsearch(): ElasticsearchSystem =
    getOrNone<ElasticsearchSystem>().getOrElse {
        throw SystemNotRegisteredException(ElasticsearchSystem::class)
    }

class ElasticsearchSystem internal constructor(
    override val testSystem: TestSystem,
    private val context: ElasticsearchContext,
) : DocumentDatabaseSystem, RunAware, AfterRunAware, ExposesConfiguration {
    private lateinit var esClient: ElasticsearchClient
    private lateinit var exposedConfiguration: ElasticSearchExposedConfiguration

    override suspend fun run() {
        context.container.start()
        exposedConfiguration = ElasticSearchExposedConfiguration(
            context.container.host,
            context.container.firstMappedPort,
            context.options.containerOptions.password
        )
    }

    override suspend fun afterRun() {
        esClient = createEsClient(exposedConfiguration, context.container.createSslContextFromCa())
        context.options.migrationCollection.run(esClient)
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

    suspend fun <T : Any> shouldQuery(
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

    override fun close(): Unit = runBlocking {
        withContext(Dispatchers.IO) {
            esClient._transport().close()
            stop()
        }
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
        sslContext: SSLContext,
    ): ElasticsearchClient {
        val credentialsProvider: CredentialsProvider = BasicCredentialsProvider()
        credentialsProvider.setCredentials(
            AuthScope.ANY,
            UsernamePasswordCredentials("elastic", exposedConfiguration.password)
        )
        val builder: RestClientBuilder =
            RestClient.builder(HttpHost(exposedConfiguration.host, exposedConfiguration.port, "https"))

        return builder.setHttpClientConfigCallback { clientBuilder: HttpAsyncClientBuilder ->
            clientBuilder.setSSLContext(sslContext)
            clientBuilder.setDefaultCredentialsProvider(credentialsProvider)
            clientBuilder
        }.build()
            .let { RestClientTransport(it, JacksonJsonpMapper(jacksonObjectMapper())) }
            .let { ElasticsearchClient(it) }
    }

    companion object {
        /**
         * Executes the given [query] and returns a list for [assertion]
         * Caller-side needs to assert based on the list
         */
        suspend inline fun <reified T : Any> ElasticsearchSystem.shouldQuery(
            query: Query,
            noinline assertion: (List<T>) -> Unit,
        ): ElasticsearchSystem = this.shouldQuery(query, assertion, T::class)
    }
}
