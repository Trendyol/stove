package com.trendyol.stove.mongodb

import com.mongodb.*
import com.mongodb.client.model.Filters.eq
import com.mongodb.kotlin.client.coroutine.MongoClient
import com.trendyol.stove.containers.StoveContainerInspectInformation
import com.trendyol.stove.functional.*
import com.trendyol.stove.reporting.Reports
import com.trendyol.stove.system.Stove
import com.trendyol.stove.system.abstractions.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.runBlocking
import org.bson.*
import org.bson.conversions.Bson
import org.bson.types.ObjectId
import org.slf4j.*

/**
 * MongoDB document database system for testing document storage operations.
 *
 * Provides a DSL for testing MongoDB operations:
 * - Document CRUD operations (save, get, delete)
 * - MongoDB queries with JSON syntax
 * - Collection management
 * - Aggregation pipelines
 *
 * ## Saving Documents
 *
 * ```kotlin
 * mongodb {
 *     // Save to default collection
 *     save("user-123", User(id = "123", name = "John"))
 *
 *     // Save to specific collection
 *     save("user-123", User(id = "123", name = "John"), collection = "users")
 * }
 * ```
 *
 * ## Retrieving Documents
 *
 * ```kotlin
 * mongodb {
 *     // Get by ObjectId and assert
 *     shouldGet<User>("507f1f77bcf86cd799439011") { user ->
 *         user.name shouldBe "John"
 *         user.email shouldBe "john@example.com"
 *     }
 *
 *     // Get from specific collection
 *     shouldGet<User>("507f1f77bcf86cd799439011", collection = "users") { user ->
 *         user.name shouldBe "John"
 *     }
 * }
 * ```
 *
 * ## Querying Documents
 *
 * ```kotlin
 * mongodb {
 *     // Query with MongoDB JSON syntax
 *     shouldQuery<User>(
 *         query = """{ "status": "active", "age": { "${"$"}gte": 18 } }""",
 *         collection = "users"
 *     ) { users ->
 *         users.size shouldBeGreaterThan 0
 *         users.all { it.status == "active" } shouldBe true
 *     }
 *
 *     // Complex queries
 *     shouldQuery<Order>(
 *         query = """{
 *             "userId": "user-123",
 *             "total": { "${"$"}gt": 100 },
 *             "status": { "${"$"}in": ["pending", "confirmed"] }
 *         }""",
 *         collection = "orders"
 *     ) { orders ->
 *         orders shouldHaveSize 2
 *     }
 * }
 * ```
 *
 * ## Deleting Documents
 *
 * ```kotlin
 * mongodb {
 *     shouldDelete("507f1f77bcf86cd799439011")
 *     shouldDelete("507f1f77bcf86cd799439011", collection = "users")
 * }
 * ```
 *
 * ## Test Workflow Example
 *
 * ```kotlin
 * test("should create user via API and store in MongoDB") {
 *     stove {
 *         // Create user via API
 *         val userId: String
 *         http {
 *             postAndExpectBody<UserResponse>(
 *                 uri = "/users",
 *                 body = CreateUserRequest(name = "John").some()
 *             ) { response ->
 *                 response.status shouldBe 201
 *                 userId = response.body().id
 *             }
 *         }
 *
 *         // Verify in MongoDB
 *         mongodb {
 *             shouldGet<User>(userId, collection = "users") { user ->
 *                 user.name shouldBe "John"
 *                 user.createdAt shouldNotBe null
 *             }
 *         }
 *     }
 * }
 * ```
 *
 * ## Configuration
 *
 * ```kotlin
 * Stove()
 *     .with {
 *         mongodb {
 *             MongodbSystemOptions(
 *                 databaseOptions = DatabaseOptions(
 *                     default = DefaultDatabase(
 *                         name = "my_database",
 *                         collection = "default_collection"
 *                     )
 *                 ),
 *                 configureExposedConfiguration = { cfg ->
 *                     listOf(
 *                         "spring.data.mongodb.uri=${cfg.connectionString}"
 *                     )
 *                 }
 *             ).migrations {
 *                 register<CreateIndexesMigration>()
 *             }
 *         }
 *     }
 * ```
 *
 * @property stove The parent test system.
 * @property context MongoDB context containing database options.
 * @see MongodbSystemOptions
 * @see MongodbExposedConfiguration
 */
@MongoDsl
class MongodbSystem internal constructor(
  override val stove: Stove,
  val context: MongodbContext
) : PluggedSystem,
  RunAware,
  ExposesConfiguration,
  Reports {
  @PublishedApi
  internal lateinit var mongoClient: MongoClient

  override val reportSystemName: String = "MongoDB"
  private lateinit var exposedConfiguration: MongodbExposedConfiguration
  private val logger: Logger = LoggerFactory.getLogger(javaClass)
  private val state: StateStorage<MongodbExposedConfiguration> =
    stove.options.createStateStorage<MongodbExposedConfiguration, MongodbSystem>()

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
    crossinline assertion: (List<T>) -> Unit
  ): MongodbSystem {
    report(
      action = "Query '$collection'",
      input = arrow.core.Some(mapOf("collection" to collection, "filter" to query))
    ) {
      val results = mongoClient
        .getDatabase(context.options.databaseOptions.default.name)
        .getCollection<Document>(collection)
        .find(BsonDocument.parse(query))
        .map { context.options.serde.deserialize(it.toJson(context.options.jsonWriterSettings), T::class.java) }
        .toList()
      assertion(results)
      results
    }
    return this
  }

  @MongoDsl
  suspend inline fun <reified T : Any> shouldGet(
    objectId: String,
    collection: String = context.options.databaseOptions.default.collection,
    crossinline assertion: (T) -> Unit
  ): MongodbSystem {
    report(
      action = "Get document",
      input = arrow.core.Some(mapOf("collection" to collection, "_id" to objectId))
    ) {
      val document = mongoClient
        .getDatabase(context.options.databaseOptions.default.name)
        .getCollection<Document>(collection)
        .find(filterById(objectId))
        .map { context.options.serde.deserialize(it.toJson(context.options.jsonWriterSettings), T::class.java) }
        .first()
      assertion(document)
      document
    }
    return this
  }

  /**
   * Saves the [instance] with given [objectId] to the [collection]
   */
  @MongoDsl
  suspend inline fun <reified T : Any> save(
    instance: T,
    objectId: String = ObjectId().toHexString(),
    collection: String = context.options.databaseOptions.default.collection
  ): MongodbSystem {
    report(
      action = "Insert document",
      input = arrow.core.Some(instance),
      metadata = mapOf("collection" to collection, "_id" to objectId)
    ) {
      mongoClient
        .getDatabase(context.options.databaseOptions.default.name)
        .getCollection<Document>(collection)
        .also { coll ->
          context.options.serde
            .serialize(instance)
            .let { BsonDocument.parse(it) }
            .let { doc -> Document(doc) }
            .append(RESERVED_ID, ObjectId(objectId))
            .let { coll.insertOne(it) }
        }
    }
    return this
  }

  @MongoDsl
  suspend fun shouldNotExist(
    objectId: String,
    collection: String = context.options.databaseOptions.default.collection
  ): MongodbSystem {
    report(
      action = "Document should not exist",
      input = arrow.core.Some(mapOf("collection" to collection, "_id" to objectId)),
      expected = arrow.core.Some("Document not found")
    ) {
      val exists = mongoClient
        .getDatabase(context.options.databaseOptions.default.name)
        .getCollection<Document>(collection)
        .find(filterById(objectId))
        .firstOrNull() != null
      if (exists) throw AssertionError("The document with the given id($objectId) was not expected, but found!")
    }
    return this
  }

  @MongoDsl
  suspend fun shouldDelete(
    objectId: String,
    collection: String = context.options.databaseOptions.default.collection
  ): MongodbSystem {
    report(
      action = "Delete document",
      metadata = mapOf("collection" to collection, "_id" to objectId)
    ) {
      mongoClient
        .getDatabase(context.options.databaseOptions.default.name)
        .getCollection<Document>(collection)
        .deleteOne(filterById(objectId))
    }
    return this
  }

  /**
   * Pauses the container. Use with care, as it will pause the container which might affect other tests.
   * This operation is not supported when using a provided instance.
   * @return MongodbSystem
   */
  @MongoDsl
  suspend fun pause(): MongodbSystem {
    report(
      action = "Pause container",
      metadata = mapOf("operation" to "fault-injection")
    ) {
      withContainerOrWarn("pause") { it.pause() }
    }
    return this
  }

  /**
   * Unpauses the container. Use with care, as it will unpause the container which might affect other tests.
   * This operation is not supported when using a provided instance.
   * @return MongodbSystem
   */
  @MongoDsl
  suspend fun unpause(): MongodbSystem {
    report(action = "Unpause container") {
      withContainerOrWarn("unpause") { it.unpause() }
    }
    return this
  }

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
    context.runtime is StoveMongoContainer -> !state.isSubsequentRun() || stove.options.runMigrationsAlways
    else -> throw UnsupportedOperationException("Unsupported runtime type: ${context.runtime::class}")
  }

  companion object {
    const val RESERVED_ID = "_id"

    @PublishedApi
    internal fun filterById(key: String): Bson = eq(RESERVED_ID, ObjectId(key))

    /**
     * Exposes the [MongoClient] to the [MongodbSystem].
     * Use this for advanced MongoDB operations not covered by the DSL.
     */
    @MongoDsl
    @Suppress("unused")
    fun MongodbSystem.client(): MongoClient = mongoClient
  }
}
