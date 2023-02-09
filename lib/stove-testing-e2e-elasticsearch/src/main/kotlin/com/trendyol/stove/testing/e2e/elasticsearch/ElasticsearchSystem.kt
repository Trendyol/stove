@file:Suppress("UNCHECKED_CAST")

package com.trendyol.stove.testing.e2e.elasticsearch

import arrow.core.getOrElse
import arrow.core.orElse
import arrow.core.toOption
import co.elastic.clients.elasticsearch.ElasticsearchClient
import co.elastic.clients.elasticsearch.core.DeleteRequest
import co.elastic.clients.elasticsearch.core.SearchRequest
import co.elastic.clients.elasticsearch.indices.CreateIndexRequest
import co.elastic.clients.json.jackson.JacksonJsonpMapper
import co.elastic.clients.transport.rest_client.RestClientTransport
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.trendyol.stove.testing.e2e.containers.withProvidedRegistry
import com.trendyol.stove.testing.e2e.database.DocumentDatabaseSystem
import com.trendyol.stove.testing.e2e.serialization.StoveJacksonJsonSerializer
import com.trendyol.stove.testing.e2e.serialization.StoveJsonSerializer
import com.trendyol.stove.testing.e2e.system.TestSystem
import com.trendyol.stove.testing.e2e.system.abstractions.ExposesConfiguration
import com.trendyol.stove.testing.e2e.system.abstractions.RunAware
import com.trendyol.stove.testing.e2e.system.abstractions.SystemNotRegisteredException
import org.apache.http.HttpHost
import org.apache.http.auth.AuthScope
import org.apache.http.auth.UsernamePasswordCredentials
import org.apache.http.client.CredentialsProvider
import org.apache.http.impl.client.BasicCredentialsProvider
import org.apache.http.impl.nio.client.HttpAsyncClientBuilder
import org.elasticsearch.client.*
import org.testcontainers.elasticsearch.ElasticsearchContainer
import javax.net.ssl.SSLContext
import kotlin.reflect.KClass

data class ElasticsearchSystemOptions(
    val configureExposedConfiguration: (ElasticSearchExposedConfiguration) -> List<String> = { _ -> listOf() },
    val jsonSerializer: StoveJsonSerializer = StoveJacksonJsonSerializer(),
    val containerOptions: ContainerOptions = ContainerOptions(),
)

data class ContainerOptions(
    val registry: String = "docker.elastic.co/",
    val imageVersion: String = "8.6.1",
    val exposedPorts: List<Int> = listOf(9200),
    val password: String = "password",
)

fun TestSystem.withElasticsearch(
    index: String,
    options: ElasticsearchSystemOptions = ElasticsearchSystemOptions(),
): TestSystem {
    val elasticsearchContainer =
        withProvidedRegistry("elasticsearch/elasticsearch:${options.containerOptions.imageVersion}", options.containerOptions.registry) {
            ElasticsearchContainer(it)
        }
    elasticsearchContainer.addExposedPorts(*options.containerOptions.exposedPorts.toIntArray())
    elasticsearchContainer.withPassword(options.containerOptions.password)
    this.getOrRegister(
        ElasticsearchSystem(this, ElasticsearchContext(index, elasticsearchContainer, options))
    )
    return this
}

data class ElasticSearchExposedConfiguration(
    val host: String,
    val port: Int,
    val password: String,
)

data class ElasticsearchContext(
    val index: String,
    val container: ElasticsearchContainer,
    val options: ElasticsearchSystemOptions,
)

fun TestSystem.elasticsearch(): ElasticsearchSystem =
    getOrNone<ElasticsearchSystem>().getOrElse {
        throw SystemNotRegisteredException(ElasticsearchSystem::class)
    }

class ElasticsearchSystem internal constructor(
    override val testSystem: TestSystem,
    private val context: ElasticsearchContext,
) : DocumentDatabaseSystem, RunAware, ExposesConfiguration {
    private lateinit var esClient: ElasticsearchClient
    private lateinit var exposedConfiguration: ElasticSearchExposedConfiguration

    override suspend fun run() {
        context.container.start()
        exposedConfiguration = ElasticSearchExposedConfiguration(
            context.container.host,
            context.container.firstMappedPort,
            context.options.containerOptions.password
        )
        esClient = createEsClient(exposedConfiguration, context.container.createSslContextFromCa())
        createIndex(context.index)
    }

    override suspend fun stop(): Unit = context.container.stop()

    override suspend fun <T : Any> shouldQuery(
        query: String,
        assertion: (List<T>) -> Unit,
        clazz: KClass<T>,
    ): ElasticsearchSystem =
        esClient.search(SearchRequest.of { it.index(context.index) }, clazz.java)
            .hits().hits()
            .mapNotNull { it.source() }
            .let { a -> assertion(a) }
            .let { this }

    override suspend fun <T : Any> shouldGet(
        key: String,
        assertion: (T) -> Unit,
        clazz: KClass<T>,
    ): ElasticsearchSystem = esClient.get({ req -> req.index(context.index).id(key) }, clazz.java)
        .source().toOption()
        .map(assertion)
        .orElse { throw AssertionError("Resource with key is not found") }
        .let { this }

    override suspend fun shouldDelete(key: String): ElasticsearchSystem = esClient
        .delete(DeleteRequest.of { req -> req.index(context.index).id(key) })
        .let { this }

    override suspend fun <T : Any> save(
        collection: String,
        id: String,
        instance: T,
    ): ElasticsearchSystem = esClient.index { req ->
        req.index(collection)
            .id(id)
            .document(instance)
    }.let { this }

    suspend fun <T : Any> save(
        id: String,
        instance: T,
    ): ElasticsearchSystem = save(context.index, id, instance)

    private fun createIndex(
        indexName: String,
    ): ElasticsearchSystem {
        val createIndexRequest: CreateIndexRequest = CreateIndexRequest.Builder().index(indexName).build()
        esClient.indices().create(createIndexRequest)
        return this
    }

    override fun close(): Unit = esClient._transport().close()

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
        credentialsProvider.setCredentials(AuthScope.ANY, UsernamePasswordCredentials("elastic", exposedConfiguration.password))
        val builder: RestClientBuilder = RestClient.builder(HttpHost(exposedConfiguration.host, exposedConfiguration.port, "https"))

        return builder.setHttpClientConfigCallback { clientBuilder: HttpAsyncClientBuilder ->
            clientBuilder.setSSLContext(sslContext)
            clientBuilder.setDefaultCredentialsProvider(credentialsProvider)
            clientBuilder
        }.build()
            .let { RestClientTransport(it, JacksonJsonpMapper(jacksonObjectMapper())) }
            .let { ElasticsearchClient(it) }
    }
}
