package com.trendyol.stove.testing.e2e.mongodb

import com.mongodb.*
import com.mongodb.client.model.Filters.eq
import com.mongodb.kotlin.client.coroutine.MongoClient
import com.trendyol.stove.functional.*
import com.trendyol.stove.testing.e2e.containers.StoveContainerInspectInformation
import com.trendyol.stove.testing.e2e.system.TestSystem
import com.trendyol.stove.testing.e2e.system.abstractions.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.runBlocking
import org.bson.*
import org.bson.conversions.Bson
import org.bson.types.ObjectId
import org.slf4j.*

@MongoDsl
class MongodbSystem internal constructor(
  override val testSystem: TestSystem,
  val context: MongodbContext
) : PluggedSystem, RunAware, ExposesConfiguration {
  @PublishedApi
  internal lateinit var mongoClient: MongoClient
  private lateinit var exposedConfiguration: MongodbExposedConfiguration
  private val logger: Logger = LoggerFactory.getLogger(javaClass)
  private val state: StateStorage<MongodbExposedConfiguration> =
    testSystem.options.createStateStorage<MongodbExposedConfiguration, MongodbSystem>()

  override suspend fun run() {
    exposedConfiguration = state.capture {
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

  override fun configuration(): List<String> = context.options.configureExposedConfiguration(exposedConfiguration)

  @MongoDsl
  suspend inline fun <reified T : Any> shouldQuery(
    query: String,
    collection: String = context.options.databaseOptions.default.collection,
    assertion: (List<T>) -> Unit
  ): MongodbSystem = mongoClient
    .getDatabase(context.options.databaseOptions.default.name)
    .getCollection<Document>(collection)
    .find(BsonDocument.parse(query))
    .map { context.options.serde.deserialize(it.toJson(context.options.jsonWriterSettings), T::class.java) }
    .toList()
    .also(assertion)
    .let { this }

  @MongoDsl
  suspend inline fun <reified T : Any> shouldGet(
    objectId: String,
    collection: String = context.options.databaseOptions.default.collection,
    assertion: (T) -> Unit
  ): MongodbSystem = mongoClient
    .getDatabase(context.options.databaseOptions.default.name)
    .getCollection<Document>(collection)
    .find(filterById(objectId))
    .map { context.options.serde.deserialize(it.toJson(context.options.jsonWriterSettings), T::class.java) }
    .first()
    .also(assertion)
    .let { this }

  /**
   * Saves the [instance] with given [objectId] to the [collection]
   */
  @MongoDsl
  suspend inline fun <reified T : Any> save(
    instance: T,
    objectId: String = ObjectId().toHexString(),
    collection: String = context.options.databaseOptions.default.collection
  ): MongodbSystem = mongoClient
    .getDatabase(context.options.databaseOptions.default.name)
    .getCollection<Document>(collection)
    .also { coll ->
      context.options.serde.serialize(instance)
        .let { BsonDocument.parse(it) }
        .let { doc -> Document(doc) }
        .append(RESERVED_ID, ObjectId(objectId))
        .let { coll.insertOne(it) }
    }
    .let { this }

  @MongoDsl
  suspend fun shouldNotExist(
    objectId: String,
    collection: String = context.options.databaseOptions.default.collection
  ): MongodbSystem {
    val exists = mongoClient
      .getDatabase(context.options.databaseOptions.default.name)
      .getCollection<Document>(collection)
      .find(filterById(objectId))
      .firstOrNull() != null
    if (exists) {
      throw AssertionError("The document with the given id($objectId) was not expected, but found!")
    }
    return this
  }

  @MongoDsl
  suspend fun shouldDelete(
    objectId: String,
    collection: String = context.options.databaseOptions.default.collection
  ): MongodbSystem = mongoClient
    .getDatabase(context.options.databaseOptions.default.name)
    .getCollection<Document>(collection)
    .deleteOne(filterById(objectId)).let { this }

  /**
   * Pauses the container. Use with care, as it will pause the container which might affect other tests.
   * @return KafkaSystem
   */
  @MongoDsl
  fun pause(): MongodbSystem = context.container.pause().let { this }

  /**
   * Unpauses the container. Use with care, as it will unpause the container which might affect other tests.
   * @return KafkaSystem
   */
  @MongoDsl
  fun unpause(): MongodbSystem = context.container.unpause().let { this }

  @MongoDsl
  fun inspect(): StoveContainerInspectInformation = context.container.inspect()

  override fun close(): Unit = runBlocking {
    Try {
      mongoClient.close()
      executeWithReuseCheck { stop() }
    }.recover {
      logger.warn("Closing mongodb got an error: $it")
    }
  }

  private fun createClient(
    exposedConfiguration: MongodbExposedConfiguration
  ): MongoClient = MongoClientSettings.builder()
    .applyConnectionString(ConnectionString(exposedConfiguration.connectionString))
    .retryWrites(true)
    .readConcern(ReadConcern.MAJORITY)
    .writeConcern(WriteConcern.MAJORITY)
    .apply(context.options.configureClient)
    .build()
    .let { MongoClient.create(it) }

  companion object {
    const val RESERVED_ID = "_id"

    @PublishedApi
    internal fun filterById(key: String): Bson = eq(RESERVED_ID, ObjectId(key))

    /**
     * Exposes the [MongoClient] to the [MongodbSystem]
     */
    @MongoDsl
    fun MongodbSystem.client(): MongoClient = mongoClient
  }
}
