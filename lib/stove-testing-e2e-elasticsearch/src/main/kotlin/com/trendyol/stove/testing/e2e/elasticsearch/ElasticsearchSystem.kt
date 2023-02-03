@file:Suppress("UNCHECKED_CAST")

package com.trendyol.stove.testing.e2e.elasticsearch

import arrow.core.getOrElse
import com.trendyol.stove.testing.e2e.containers.withProvidedRegistry
import com.trendyol.stove.testing.e2e.database.DatabaseSystem
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
import org.elasticsearch.action.DocWriteRequest
import org.elasticsearch.action.get.GetRequest
import org.elasticsearch.action.index.IndexRequest
import org.elasticsearch.client.RequestOptions
import org.elasticsearch.client.RestClient
import org.elasticsearch.client.RestClientBuilder
import org.elasticsearch.client.RestHighLevelClient
import org.elasticsearch.client.indices.CreateIndexRequest
import org.elasticsearch.common.xcontent.XContentFactory
import org.elasticsearch.common.xcontent.XContentType
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.testcontainers.elasticsearch.ElasticsearchContainer
import kotlin.reflect.KClass

data class ElasticsearchSystemOptions(
    val configureExposedConfiguration: (ElasticSearchExposedConfiguration) -> List<String> = { _ -> listOf() },
    val jsonSerializer: StoveJsonSerializer = StoveJacksonJsonSerializer(),
)

fun TestSystem.withElasticsearch(
    registry: String = "docker.elastic.co/",
    // options: ElasticsearchSystemOptions = ElasticsearchSystemOptions(),
): TestSystem {
    val elasticsearchContainer =
        withProvidedRegistry("elasticsearch/elasticsearch:7.10.2", registry) {
            ElasticsearchContainer(it)
        }
    elasticsearchContainer.addExposedPorts(9200)
    // elasticsearchContainer.withPassword("s3cret")
    this.getOrRegister(
        ElasticsearchSystem(this, ElasticsearchContext(elasticsearchContainer, ElasticsearchSystemOptions()))
    )
    return this
}

data class ElasticSearchExposedConfiguration(
    val host: String,
    val port: Int,
    val username: String,
    val password: String,
)

data class ElasticsearchContext(
    // val bucket: BucketDefinition,
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
) : DatabaseSystem, RunAware, ExposesConfiguration {

    private lateinit var client: RestHighLevelClient

    // private lateinit var collection: ReactiveCollection
    private lateinit var exposedConfiguration: ElasticSearchExposedConfiguration
    private val objectMapper: StoveJsonSerializer = context.options.jsonSerializer

    // private val objectMapper: StoveJsonSerializer = context.options.jsonSerializer
    private val logger: Logger = LoggerFactory.getLogger(javaClass)

    object Constants {
        const val ONE_MINUTE = 60 * 1000
        const val ONE_SECOND = 1000
    }

    override suspend fun run() {
        context.container.start()
        exposedConfiguration = ElasticSearchExposedConfiguration(
            context.container.host,
            context.container.firstMappedPort,
            "elastic",
            "s3cret"
        )
        client = createCluster(exposedConfiguration)
        // collection = createDefaultCollection()
    }

    override suspend fun stop() {
        context.container.stop()
    }

    // override fun configuration(): List<String> {
    //     // return context.options.configureExposedConfiguration(exposedConfiguration) +
    //     //     listOf(
    //     //         "couchbase.hosts=${exposedConfiguration.hostsWithPort}",
    //     //         "couchbase.username=${context.container.username}",
    //     //         "couchbase.password=${context.container.password}"
    //     //     )
    // }

    // override suspend fun <T : Any> shouldQuery(
    //     query: String,
    //     assertion: (List<T>) -> Unit,
    //     clazz: KClass<T>,
    // ): ElasticsearchSystem {
    //     val result = cluster.executeQueryAs<Any>(query) { queryOptions -> queryOptions.scanConsistency(REQUEST_PLUS) }
    //
    //     val objects = result
    //         .map { objectMapper.serialize(it) }
    //         .map { objectMapper.deserialize(it, clazz) }
    //
    //     assertion(objects)
    //     return this
    // }

    fun <T : Any> shouldGets(
        indexName: String,
        id: String,
        clazz: KClass<T>,
        assertion: (T) -> Unit,
    ): ElasticsearchSystem {
        val result = client.get(GetRequest(indexName, id), RequestOptions.DEFAULT)
        val actual = objectMapper.deserialize(result.sourceAsString, clazz)
        assertion(actual)

        return this
    }
    //
    // override suspend fun shouldDelete(id: String): ElasticsearchSystem {
    //     collection.remove(id).awaitSingle()
    //     return this
    // }
    //
    // /**
    //  * Saves the [instance] with given [id] to the [collection]
    //  * To save to the default collection use [saveToDefaultCollection]
    //  */
    // suspend fun <T : Any> save(
    //     collection: String,
    //     id: String,
    //     instance: T,
    // ): ElasticsearchSystem {
    //     cluster
    //         .bucket(context.bucket.name)
    //         .collection(collection)
    //         .insert(
    //             id,
    //             JsonObject.fromJson(objectMapper.serialize(instance)),
    //             InsertOptions.insertOptions().durability(PERSIST_TO_MAJORITY)
    //         )
    //         .awaitSingle()
    //
    //     return this
    // }
    //
    // /**
    //  * Saves the [instance] with given [id] to the default collection
    //  * In couchbase the default collection is `_default`
    //  */
    // suspend inline fun <reified T : Any> saveToDefaultCollection(
    //     id: String,
    //     instance: T,
    // ): ElasticsearchSystem = this.save("_default", id, instance)
    //
    // override fun close(): Unit = runBlocking {
    //     Try { cluster.disconnect().awaitSingle() }.recover {
    //         logger.warn("Disconnecting the couchbase cluster got an error: $it")
    //     }
    // }

    // private fun createDefaultCollection(): ReactiveCollection = cluster.bucket(context.bucket.name).defaultCollection()

    fun <T : Any> save(
        index: String,
        id: String,
        instance: T,
    ): ElasticsearchSystem {
        val request = IndexRequest(index).id(id)
        val jsonString = objectMapper.serialize(instance)
        request.source(jsonString, XContentType.JSON)
        client.index(request, RequestOptions.DEFAULT)
        return this
    }

    private fun createCluster(exposedConfiguration: ElasticSearchExposedConfiguration): RestHighLevelClient {
        val requestConfigCallback = RestClientBuilder.RequestConfigCallback { requestConfigBuilder: Builder ->
            requestConfigBuilder
                .setConnectionRequestTimeout(0)
                .setSocketTimeout(ONE_MINUTE)
                .setConnectTimeout(ONE_SECOND * 5)
        }
        val builder = RestClient.builder(HttpHost(exposedConfiguration.host, exposedConfiguration.port, "http"))
            .setHttpClientConfigCallback { config: HttpAsyncClientBuilder ->
                config.setMaxConnPerRoute(1000)
                config.setMaxConnTotal(2000)
                config
            }
            .setRequestConfigCallback(requestConfigCallback)
        //        builder.setMaxRetryTimeoutMillis(ONE_MINUTE);
        return RestHighLevelClient(builder)
    }

    override suspend fun <T : Any> shouldQuery(
        query: String,
        assertion: (List<T>) -> Unit,
        clazz: KClass<T>,
    ): DatabaseSystem {
        TODO("Not yet implemented")
    }

    override suspend fun <T : Any> shouldGet(
        id: String,
        assertion: (T) -> Unit,
        clazz: KClass<T>,
    ): DatabaseSystem {
        TODO("Not yet implemented")
    }

    override suspend fun shouldDelete(id: String): DatabaseSystem {
        TODO("Not yet implemented")
    }

    override fun close() {
        client.close()
    }

    override fun configuration(): List<String> {
        return listOf()
    }

    // private fun jsonSerializer(): JsonSerializer = object : JsonSerializer {
    //     override fun serialize(input: Any): ByteArray = objectMapper.serializeAsBytes(input)
    //
    //     override fun <T : Any> deserialize(
    //         target: Class<T>,
    //         input: ByteArray,
    //     ): T = objectMapper.deserialize(input, Reflection.getOrCreateKotlinClass(target)) as T
    // }
}
