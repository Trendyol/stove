@file:Suppress("UNCHECKED_CAST")

package com.trendyol.stove.testing.e2e.mongodb

import com.fasterxml.jackson.module.kotlin.convertValue
import com.mongodb.*
import com.mongodb.client.model.Filters.eq
import com.mongodb.reactivestreams.client.MongoClient
import com.mongodb.reactivestreams.client.MongoClients
import com.trendyol.stove.functional.Try
import com.trendyol.stove.functional.recover
import com.trendyol.stove.testing.e2e.database.DocumentDatabaseSystem
import com.trendyol.stove.testing.e2e.system.TestSystem
import com.trendyol.stove.testing.e2e.system.abstractions.*
import kotlinx.coroutines.reactive.awaitFirst
import kotlinx.coroutines.runBlocking
import org.bson.BsonDocument
import org.bson.Document
import org.bson.conversions.Bson
import org.bson.json.JsonWriterSettings
import org.bson.types.ObjectId
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import reactor.kotlin.core.publisher.toFlux
import kotlin.reflect.KClass

class MongodbSystem internal constructor(
    override val testSystem: TestSystem,
    private val context: MongodbContext,
) : DocumentDatabaseSystem, RunAware, ExposesConfiguration {

    private lateinit var mongoClient: MongoClient
    private lateinit var exposedConfiguration: MongodbExposedConfiguration
    private val logger: Logger = LoggerFactory.getLogger(javaClass)
    private val jsonWriterSettings: JsonWriterSettings = JsonWriterSettings.builder()
        .objectIdConverter { value, writer -> writer.writeString(value.toHexString()) }
        .build()
    private val state: StateOfSystem<MongodbSystem, MongodbExposedConfiguration> =
        StateOfSystem(testSystem.options, MongodbSystem::class, MongodbExposedConfiguration::class)

    override suspend fun run() {
        exposedConfiguration = state.capture {
            context.container.start()
            MongodbExposedConfiguration(
                context.container.connectionString
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

    override suspend fun <T : Any> shouldQuery(
        query: String,
        assertion: (List<T>) -> Unit,
        clazz: KClass<T>,
    ): MongodbSystem = mongoClient.getDatabase(context.options.databaseOptions.default.name)
        .let { it.withCodecRegistry(PojoRegistry(it.codecRegistry).register(clazz).build()) }
        .getCollection(context.options.databaseOptions.default.collection)
        .find(BsonDocument.parse(query))
        .toFlux()
        .map { (it as Document).toJson(jsonWriterSettings) }
        .map { context.options.objectMapper.readValue(it, clazz.java) }
        .collectList()
        .awaitFirst()
        .also(assertion)
        .let { this }

    override suspend fun <T : Any> shouldGet(
        key: String,
        assertion: (T) -> Unit,
        clazz: KClass<T>,
    ): MongodbSystem = mongoClient.getDatabase(context.options.databaseOptions.default.name)
        .getCollection(context.options.databaseOptions.default.collection)
        .let { it.withCodecRegistry(PojoRegistry(it.codecRegistry).register(clazz).build()) }
        .find(filterById(key))
        .awaitFirst()
        .let { it as Document }.toJson(jsonWriterSettings)
        .let { context.options.objectMapper.readValue(it, clazz.java) }
        .also(assertion)
        .let { this }

    override suspend fun shouldDelete(
        key: String,
    ): MongodbSystem = mongoClient.getDatabase(context.options.databaseOptions.default.name)
        .getCollection(context.options.databaseOptions.default.collection)
        .deleteOne(filterById(key)).awaitFirst().let { this }

    /**
     * Saves the [instance] with given [id] to the [collection]
     */
    override suspend fun <T : Any> save(
        collection: String,
        id: String,
        instance: T,
    ): MongodbSystem = mongoClient.getDatabase(context.options.databaseOptions.default.name)
        .let { it.withCodecRegistry(PojoRegistry(it.codecRegistry).register(instance::class).build()) }
        .getCollection(collection)
        .let {
            val map = context.options.objectMapper.convertValue<MutableMap<String, Any>>(instance)
            map[RESERVED_ID] = ObjectId(id)
            it.insertOne(Document(map))
        }
        .awaitFirst()
        .let { this }

    suspend fun <T : Any> saveToDefaultCollection(
        id: String,
        instance: T,
    ): MongodbSystem = save(context.options.databaseOptions.default.collection, id, instance)

    override fun close(): Unit = runBlocking {
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
        private fun filterById(key: String): Bson = eq(RESERVED_ID, ObjectId(key))
    }
}
