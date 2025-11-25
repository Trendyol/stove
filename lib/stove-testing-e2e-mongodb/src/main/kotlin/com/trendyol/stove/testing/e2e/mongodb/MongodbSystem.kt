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
) : PluggedSystem,
  RunAware,
  ExposesConfiguration {
  @PublishedApi
  internal lateinit var mongoClient: MongoClient
  private lateinit var exposedConfiguration: MongodbExposedConfiguration
  private val logger: Logger = LoggerFactory.getLogger(javaClass)
  private val state: StateStorage<MongodbExposedConfiguration> =
    testSystem.options.createStateStorage<MongodbExposedConfiguration, MongodbSystem>()

  override suspend fun run() {
    exposedConfiguration = obtainExposedConfiguration()
    mongoClient = createClient(exposedConfiguration)
    runMigrationsIfNeeded()
  }

  override suspend fun stop(): Unit = whenContainer { it.stop() }

  override fun configuration(): List<String> = context.options.configureExposedConfiguration(exposedConfiguration)

  override fun close(): Unit = runBlocking {
    Try {
      context.options.cleanup(mongoClient)
      mongoClient.close()
      executeWithReuseCheck { stop() }
    }.recover {
      logger.warn("Closing mongodb got an error: $it")
    }
  }

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
      context.options.serde
        .serialize(instance)
        .let { BsonDocument.parse(it) }
        .let { doc -> Document(doc) }
        .append(RESERVED_ID, ObjectId(objectId))
        .let { coll.insertOne(it) }
    }.let { this }

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
    .deleteOne(filterById(objectId))
    .let { this }

  /**
   * Pauses the container. Use with care, as it will pause the container which might affect other tests.
   * This operation is not supported when using a provided instance.
   * @return MongodbSystem
   */
  @MongoDsl
  fun pause(): MongodbSystem = withContainerOrWarn("pause") { it.pause() }

  /**
   * Unpauses the container. Use with care, as it will unpause the container which might affect other tests.
   * This operation is not supported when using a provided instance.
   * @return MongodbSystem
   */
  @MongoDsl
  fun unpause(): MongodbSystem = withContainerOrWarn("unpause") { it.unpause() }

  /**
   * Inspects the container. This operation is not supported when using a provided instance.
   */
  @MongoDsl
  fun inspect(): StoveContainerInspectInformation? = when (val runtime = context.runtime) {
    is StoveMongoContainer -> {
      runtime.inspect()
    }

    is ProvidedRuntime -> {
      logger.warn("inspect() is not supported when using a provided instance")
      null
    }

    else -> {
      throw UnsupportedOperationException("Unsupported runtime type: ${runtime::class}")
    }
  }

  private suspend fun obtainExposedConfiguration(): MongodbExposedConfiguration =
    when {
      context.options is ProvidedMongodbSystemOptions -> context.options.config
      context.runtime is StoveMongoContainer -> startMongoContainer(context.runtime)
      else -> throw UnsupportedOperationException("Unsupported runtime type: ${context.runtime::class}")
    }

  private suspend fun startMongoContainer(container: StoveMongoContainer): MongodbExposedConfiguration =
    state.capture {
      container.start()
      MongodbExposedConfiguration(
        connectionString = container.connectionString,
        host = container.host,
        port = container.firstMappedPort,
        replicaSetUrl = container.replicaSetUrl
      )
    }

  private fun createClient(config: MongodbExposedConfiguration): MongoClient =
    MongoClientSettings
      .builder()
      .applyConnectionString(ConnectionString(config.connectionString))
      .retryWrites(true)
      .readConcern(ReadConcern.MAJORITY)
      .writeConcern(WriteConcern.MAJORITY)
      .apply(context.options.configureClient)
      .build()
      .let { MongoClient.create(it) }

  private inline fun withContainerOrWarn(
    operation: String,
    action: (StoveMongoContainer) -> Unit
  ): MongodbSystem = when (val runtime = context.runtime) {
    is StoveMongoContainer -> {
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

  private inline fun whenContainer(action: (StoveMongoContainer) -> Unit) {
    if (context.runtime is StoveMongoContainer) {
      action(context.runtime)
    }
  }

  private suspend fun runMigrationsIfNeeded() {
    if (shouldRunMigrations()) {
      context.options.migrationCollection.run(MongodbMigrationContext(mongoClient, context.options))
    }
  }

  private fun shouldRunMigrations(): Boolean = when {
    context.options is ProvidedMongodbSystemOptions -> context.options.runMigrations
    context.runtime is StoveMongoContainer -> !state.isSubsequentRun() || testSystem.options.runMigrationsAlways
    else -> throw UnsupportedOperationException("Unsupported runtime type: ${context.runtime::class}")
  }

  companion object {
    const val RESERVED_ID = "_id"

    @PublishedApi
    internal fun filterById(key: String): Bson = eq(RESERVED_ID, ObjectId(key))

    /**
     * Exposes the [MongoClient] to the [MongodbSystem]
     */
    @MongoDsl
    @Suppress("unused")
    fun MongodbSystem.client(): MongoClient = mongoClient
  }
}
