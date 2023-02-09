@file:Suppress("UNCHECKED_CAST")

package com.trendyol.stove.testing.e2e.elasticsearch

import arrow.core.getOrElse
import co.elastic.clients.elasticsearch.ElasticsearchClient
import co.elastic.clients.elasticsearch.core.DeleteRequest
import co.elastic.clients.elasticsearch.indices.CreateIndexRequest
import co.elastic.clients.json.jackson.JacksonJsonpMapper
import co.elastic.clients.transport.rest_client.RestClientTransport
import com.fasterxml.jackson.databind.ObjectMapper
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
import org.elasticsearch.ResourceNotFoundException
import org.elasticsearch.client.Request
import org.elasticsearch.client.RestClient
import org.elasticsearch.client.RestClientBuilder
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.testcontainers.elasticsearch.ElasticsearchContainer
import kotlin.reflect.KClass

data class ElasticsearchSystemOptions(
    val configureExposedConfiguration: (ElasticSearchExposedConfiguration) -> List<String> = { _ -> listOf() },
    val jsonSerializer: StoveJsonSerializer = StoveJacksonJsonSerializer(),
    val containerOptions: ContainerOptions = ContainerOptions(),
)

data class ContainerOptions(
    val registry: String = "docker.elastic.co/",
    val imageVersion: String = "8.6.0",
    val exposedPort: Int = 9200,
    val password: String = "pswd",
)

fun TestSystem.withElasticsearch(
    index: String,
    options: ElasticsearchSystemOptions = ElasticsearchSystemOptions(),
): TestSystem {
    val elasticsearchContainer =
        withProvidedRegistry("elasticsearch/elasticsearch:${options.containerOptions.imageVersion}", options.containerOptions.registry) {
            ElasticsearchContainer(it)
        }
    elasticsearchContainer.withPassword(options.containerOptions.password)
    elasticsearchContainer.addExposedPorts(options.containerOptions.exposedPort)
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
    private lateinit var restClient: RestClient
    private lateinit var elasticsearchClient: ElasticsearchClient

    private lateinit var exposedConfiguration: ElasticSearchExposedConfiguration
    private val objectMapper: StoveJsonSerializer = context.options.jsonSerializer

    private val logger: Logger = LoggerFactory.getLogger(javaClass)

    override suspend fun run() {
        context.container.start()
        exposedConfiguration = ElasticSearchExposedConfiguration(
            context.container.host,
            context.container.firstMappedPort,
            context.options.containerOptions.password
        )
        restClient = createRestClient(exposedConfiguration, context.container)
        elasticsearchClient = createElasticsearchClient(restClient)
        createIndex(context.index)
    }

    override suspend fun stop() {
        context.container.stop()
    }

    override suspend fun <T : Any> shouldQuery(
        query: String,
        assertion: (List<T>) -> Unit,
        clazz: KClass<T>,
    ): ElasticsearchSystem {
        val request = Request("GET", "${context.index}/_search")
        val response = restClient.performRequest(request)
        val jsonNode = ObjectMapper().readTree(response.entity.content.reader().readText()).get("hits").get("hits")
        val queryResults = jsonNode.map {
            objectMapper.deserialize(it.get("_source").toString(), clazz)
        }
        assertion(queryResults)
        return this
    }

    override suspend fun <T : Any> shouldGet(
        key: String,
        assertion: (T) -> Unit,
        clazz: KClass<T>,
    ): ElasticsearchSystem {
        val response = elasticsearchClient.get(
            { g ->
                g.index(context.index).id(key)
            },
            clazz.java
        )

        if (!response.found()) throw ResourceNotFoundException("Resource with id is not found")

        assertion(response.source()!!)
        return this
    }

    override suspend fun shouldDelete(key: String): ElasticsearchSystem {
        elasticsearchClient.delete(DeleteRequest.of { d -> d.index(context.index).id(key) })
        return this
    }

    override suspend fun <T : Any> save(
        collection: String,
        id: String,
        instance: T,
    ): ElasticsearchSystem {
        elasticsearchClient.index { i ->
            i.index(collection)
                .id(id)
                .document(instance)
        }
        return this
    }

    suspend fun <T : Any> save(
        id: String,
        instance: T,
    ): ElasticsearchSystem {
        return save(this.context.index, id, instance)
    }

    fun createIndex(
        indexName: String,
    ): ElasticsearchSystem {
        val createIndexRequest: CreateIndexRequest = CreateIndexRequest.Builder().index(indexName).build()
        elasticsearchClient.indices().create(createIndexRequest)
        return this
    }

    override fun close() {
        restClient.close()
    }

    override fun configuration(): List<String> {
        return context.options.configureExposedConfiguration(exposedConfiguration) +
            listOf(
                "elasticsearch.host=${exposedConfiguration.host}",
                "elasticsearch.port=${exposedConfiguration.port}"
            )
    }

    private fun createRestClient(
        exposedConfiguration: ElasticSearchExposedConfiguration,
        container: ElasticsearchContainer,
    ): RestClient {
        val credentialsProvider: CredentialsProvider = BasicCredentialsProvider()
        credentialsProvider.setCredentials(AuthScope.ANY, UsernamePasswordCredentials("elastic", exposedConfiguration.password))
        val builder: RestClientBuilder = RestClient.builder(HttpHost(exposedConfiguration.host, exposedConfiguration.port, "https"))

        builder.setHttpClientConfigCallback { clientBuilder: HttpAsyncClientBuilder ->
            clientBuilder.setSSLContext(container.createSslContextFromCa())
            clientBuilder.setDefaultCredentialsProvider(credentialsProvider)
            clientBuilder
        }
        return builder.build()
    }

    private fun createElasticsearchClient(restClient: RestClient): ElasticsearchClient {
        val restClientTransport = RestClientTransport(restClient, JacksonJsonpMapper(jacksonObjectMapper()))
        return ElasticsearchClient(restClientTransport)
    }
}
