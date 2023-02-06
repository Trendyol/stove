@file:Suppress("UNCHECKED_CAST")

package com.trendyol.stove.testing.e2e.elasticsearch

import arrow.core.getOrElse
import com.fasterxml.jackson.databind.ObjectMapper
import com.trendyol.stove.testing.e2e.containers.withProvidedRegistry
import com.trendyol.stove.testing.e2e.database.DatabaseSystem
import com.trendyol.stove.testing.e2e.database.DocumentDatabaseSystem
import com.trendyol.stove.testing.e2e.elasticsearch.ElasticsearchSystem.Constants.ONE_MINUTE
import com.trendyol.stove.testing.e2e.elasticsearch.ElasticsearchSystem.Constants.ONE_SECOND
import com.trendyol.stove.testing.e2e.serialization.StoveJacksonJsonSerializer
import com.trendyol.stove.testing.e2e.serialization.StoveJsonSerializer
import com.trendyol.stove.testing.e2e.system.TestSystem
import com.trendyol.stove.testing.e2e.system.abstractions.ExposesConfiguration
import com.trendyol.stove.testing.e2e.system.abstractions.RunAware
import com.trendyol.stove.testing.e2e.system.abstractions.SystemNotRegisteredException
import org.apache.http.HttpHost
import org.apache.http.client.config.RequestConfig.Builder
import org.apache.http.impl.nio.client.HttpAsyncClientBuilder
import org.elasticsearch.action.delete.DeleteRequest
import org.elasticsearch.action.get.GetRequest
import org.elasticsearch.action.index.IndexRequest
import org.elasticsearch.client.Request
import org.elasticsearch.client.RequestOptions
import org.elasticsearch.client.RestClient
import org.elasticsearch.client.RestClientBuilder
import org.elasticsearch.client.RestHighLevelClient
import org.elasticsearch.common.xcontent.XContentType
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.testcontainers.elasticsearch.ElasticsearchContainer
import kotlin.reflect.KClass

data class ElasticsearchSystemOptions(
    val configureExposedConfiguration: (ElasticSearchExposedConfiguration) -> List<String> = { _ -> listOf() },
    val jsonSerializer: StoveJsonSerializer = StoveJacksonJsonSerializer(),
    val containerOptions: ContainerOptions = ContainerOptions()
)

data class ContainerOptions(
    val registry: String = "docker.elastic.co/",
    val imageVersion: String = "7.10.2",
    val exposedPort: Int = 9200
)

fun TestSystem.withElasticsearch(
    index: String,
    options: ElasticsearchSystemOptions = ElasticsearchSystemOptions(),
): TestSystem {
    val elasticsearchContainer =
        withProvidedRegistry("elasticsearch/elasticsearch:${options.containerOptions.imageVersion}", options.containerOptions.registry) {
            ElasticsearchContainer(it)
        }
    elasticsearchContainer.addExposedPorts(options.containerOptions.exposedPort)
    this.getOrRegister(
        ElasticsearchSystem(this, ElasticsearchContext(index, elasticsearchContainer, options))
    )
    return this
}

data class ElasticSearchExposedConfiguration(
    val host: String,
    val port: Int,
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
    private lateinit var client: RestHighLevelClient

    private lateinit var exposedConfiguration: ElasticSearchExposedConfiguration
    private val objectMapper: StoveJsonSerializer = context.options.jsonSerializer

    private val logger: Logger = LoggerFactory.getLogger(javaClass)

    object Constants {
        const val ONE_MINUTE = 60 * 1000
        const val ONE_SECOND = 1000
    }

    override suspend fun run() {
        context.container.start()
        exposedConfiguration = ElasticSearchExposedConfiguration(
            context.container.host,
            context.container.firstMappedPort
        )
        client = createCluster(exposedConfiguration)
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
        val response = client.lowLevelClient.performRequest(request)
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
        val result = client.get(GetRequest(context.index, key), RequestOptions.DEFAULT)
        val actual = objectMapper.deserialize(result.sourceAsString, clazz)
        assertion(actual)

        return this
    }

    override suspend fun shouldDelete(key: String): DatabaseSystem {
        client.delete(DeleteRequest(context.index, key), RequestOptions.DEFAULT)
        return this
    }

    override suspend fun <T : Any> save(
        collection: String,
        id: String,
        instance: T,
    ): ElasticsearchSystem {
        val request = IndexRequest(collection).id(id)
        val jsonInstance = objectMapper.serialize(instance)
        request.source(jsonInstance, XContentType.JSON)
        client.index(request, RequestOptions.DEFAULT)
        return this
    }

    suspend fun <T : Any> save(
        id: String,
        instance: T,
    ): ElasticsearchSystem {
        return save(this.context.index, id, instance)
    }

    override fun close() {
        client.close()
    }

    override fun configuration(): List<String> {
        return context.options.configureExposedConfiguration(exposedConfiguration) +
            listOf(
                "elasticsearch.host=${exposedConfiguration.host}",
                "elasticsearch.port=${exposedConfiguration.port}"
            )
    }

    private fun createCluster(exposedConfiguration: ElasticSearchExposedConfiguration): RestHighLevelClient {
        val builder = RestClient.builder(HttpHost(exposedConfiguration.host, exposedConfiguration.port, "http"))
        return RestHighLevelClient(builder)
    }
}
