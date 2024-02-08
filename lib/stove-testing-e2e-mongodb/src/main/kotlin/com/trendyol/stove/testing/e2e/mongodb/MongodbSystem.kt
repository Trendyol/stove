package com.trendyol.stove.testing.e2e.mongodb

import com.fasterxml.jackson.module.kotlin.convertValue
import com.mongodb.*
import com.mongodb.client.model.Filters.eq
import com.mongodb.reactivestreams.client.*
import com.trendyol.stove.functional.*
import com.trendyol.stove.testing.e2e.system.TestSystem
import com.trendyol.stove.testing.e2e.system.abstractions.*
import com.trendyol.stove.testing.e2e.system.annotations.StoveDsl
import kotlinx.coroutines.reactive.*
import kotlinx.coroutines.runBlocking
import org.bson.*
import org.bson.conversions.Bson
import org.bson.json.JsonWriterSettings
import org.bson.types.ObjectId
import org.slf4j.*
import reactor.kotlin.core.publisher.toFlux
import kotlin.collections.List
import kotlin.collections.MutableMap
import kotlin.collections.isNullOrEmpty
import kotlin.collections.listOf
import kotlin.collections.plus
import kotlin.collections.set

@StoveDsl
class MongodbSystem internal constructor(
    override val testSystem: TestSystem,
    val context: MongodbContext
) : PluggedSystem, RunAware, ExposesConfiguration {
    @PublishedApi
    internal lateinit var mongoClient: MongoClient
    private lateinit var exposedConfiguration: MongodbExposedConfiguration
    private val logger: Logger = LoggerFactory.getLogger(javaClass)

    @PublishedApi
    internal val jsonWriterSettings: JsonWriterSettings =
        JsonWriterSettings.builder()
            .objectIdConverter { value, writer -> writer.writeString(value.toHexString()) }
            .build()
    private val state: StateOfSystem<MongodbSystem, MongodbExposedConfiguration> =
        StateOfSystem(testSystem.options, MongodbSystem::class, MongodbExposedConfiguration::class)

    override suspend fun run() {
        exposedConfiguration =
            state.capture {
                context.container.start()
                MongodbExposedConfiguration(
                    context.container.connectionString,
                    context.container.host,
                    context.container.firstMappedPort,
                    context.container.replicaSetUrl
                )
            }
        mongoClient = createClient(exposedConfiguration)
    }

    override suspend fun stop(): Unit = context.container.stop()

    override fun configuration(): List<String> {
        return context.options.configureExposedConfiguration(exposedConfiguration) +
            listOf(
                "mongodb.connectionString=${exposedConfiguration.connectionString}"
            )
    }

    @MongoDsl
    suspend inline fun <reified T : Any> shouldQuery(
        query: String,
        assertion: (List<T>) -> Unit
    ): MongodbSystem =
        mongoClient.getDatabase(context.options.databaseOptions.default.name)
            .let { it.withCodecRegistry(PojoRegistry(it.codecRegistry).register(T::class).build()) }
            .getCollection(context.options.databaseOptions.default.collection)
            .find(BsonDocument.parse(query))
            .toFlux()
            .map { (it as Document).toJson(jsonWriterSettings) }
            .map { context.options.objectMapper.readValue(it, T::class.java) }
            .collectList()
            .awaitFirst()
            .also(assertion)
            .let { this }

    @MongoDsl
    suspend inline fun <reified T : Any> shouldGet(
        key: String,
        assertion: (T) -> Unit
    ): MongodbSystem =
        mongoClient.getDatabase(context.options.databaseOptions.default.name)
            .getCollection(context.options.databaseOptions.default.collection)
            .let { it.withCodecRegistry(PojoRegistry(it.codecRegistry).register(T::class).build()) }
            .find(filterById(key))
            .awaitFirst()
            .let { it as Document }.toJson(jsonWriterSettings)
            .let { context.options.objectMapper.readValue(it, T::class.java) }
            .also(assertion)
            .let { this }

    @MongoDsl
    suspend fun shouldNotExist(key: String): MongodbSystem {
        val isExistById =
            !mongoClient.getDatabase(context.options.databaseOptions.default.name)
                .getCollection(context.options.databaseOptions.default.collection)
                .find(filterById(key)).awaitFirstOrNull().isNullOrEmpty()
        if (isExistById) {
            throw AssertionError("The document with the given id($key) was not expected, but found!")
        }
        return this
    }

    @MongoDsl
    suspend fun shouldDelete(key: String): MongodbSystem =
        mongoClient.getDatabase(context.options.databaseOptions.default.name)
            .getCollection(context.options.databaseOptions.default.collection)
            .deleteOne(filterById(key)).awaitFirst().let { this }

    /**
     * Saves the [instance] with given [id] to the [collection]
     */
    @MongoDsl
    suspend fun <T : Any> save(
        collection: String,
        id: String,
        instance: T
    ): MongodbSystem =
        mongoClient.getDatabase(context.options.databaseOptions.default.name)
            .let { it.withCodecRegistry(PojoRegistry(it.codecRegistry).register(instance::class).build()) }
            .getCollection(collection)
            .let {
                val map = context.options.objectMapper.convertValue<MutableMap<String, Any>>(instance)
                map[RESERVED_ID] = ObjectId(id)
                it.insertOne(Document(map))
            }
            .awaitFirst()
            .let { this }

    @MongoDsl
    suspend fun <T : Any> saveToDefaultCollection(
        id: String,
        instance: T
    ): MongodbSystem = save(context.options.databaseOptions.default.collection, id, instance)

    override fun close(): Unit =
        runBlocking {
            Try {
                mongoClient.close()
                executeWithReuseCheck { stop() }
            }.recover {
                logger.warn("Closing mongodb got an error: $it")
            }
        }

    private fun createClient(exposedConfiguration: MongodbExposedConfiguration): MongoClient =
        MongoClientSettings.builder()
            .applyConnectionString(ConnectionString(exposedConfiguration.connectionString))
            .retryWrites(true)
            .readConcern(ReadConcern.MAJORITY)
            .writeConcern(WriteConcern.MAJORITY)
            .build().let { MongoClients.create(it) }

    companion object {
        private const val RESERVED_ID = "_id"

        @PublishedApi
        internal fun filterById(key: String): Bson = eq(RESERVED_ID, ObjectId(key))

        /**
         * Exposes the [MongoClient] to the [MongodbSystem]
         */
        @MongoDsl
        fun MongodbSystem.client(): MongoClient = mongoClient
    }
}
