@file:Suppress("unused")

package com.trendyol.stove.testing.e2e.couchbase

import com.couchbase.client.kotlin.*
import com.couchbase.client.kotlin.Collection
import com.couchbase.client.kotlin.codec.typeRef
import com.couchbase.client.kotlin.query.*
import com.trendyol.stove.functional.*
import com.trendyol.stove.testing.e2e.reporting.Reports
import com.trendyol.stove.testing.e2e.system.TestSystem
import com.trendyol.stove.testing.e2e.system.abstractions.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.runBlocking
import org.slf4j.*

/**
 * Couchbase document database system for testing document storage operations.
 *
 * Provides a DSL for testing Couchbase operations:
 * - Document CRUD operations (save, get, delete)
 * - N1QL queries
 * - Collection management
 * - Existence checks
 *
 * ## Saving Documents
 *
 * ```kotlin
 * couchbase {
 *     // Save to default collection
 *     save("user::123", User(id = "123", name = "John"))
 *
 *     // Save to specific collection
 *     save("users", "user::123", User(id = "123", name = "John"))
 *
 *     // Save with custom options
 *     saveWithOptions("user::123", user) { options ->
 *         options.expiry(Duration.ofHours(24))
 *     }
 * }
 * ```
 *
 * ## Retrieving Documents
 *
 * ```kotlin
 * couchbase {
 *     // Get from default collection and assert
 *     shouldGet<User>("user::123") { user ->
 *         user.name shouldBe "John"
 *         user.email shouldBe "john@example.com"
 *     }
 *
 *     // Get from specific collection
 *     shouldGet<User>("users", "user::123") { user ->
 *         user.name shouldBe "John"
 *     }
 * }
 * ```
 *
 * ## N1QL Queries
 *
 * ```kotlin
 * couchbase {
 *     // Execute N1QL query and assert results
 *     shouldQuery<User>(
 *         "SELECT * FROM `my-bucket` WHERE type = 'user' AND status = 'active'"
 *     ) { users ->
 *         users.size shouldBeGreaterThan 0
 *         users.all { it.status == "active" } shouldBe true
 *     }
 * }
 * ```
 *
 * ## Deleting Documents
 *
 * ```kotlin
 * couchbase {
 *     // Delete from default collection
 *     shouldDelete("user::123")
 *
 *     // Delete from specific collection
 *     shouldDelete("users", "user::123")
 * }
 * ```
 *
 * ## Existence Checks
 *
 * ```kotlin
 * couchbase {
 *     // Assert document doesn't exist
 *     shouldNotExist("user::deleted")
 *
 *     // In specific collection
 *     shouldNotExist("users", "user::deleted")
 * }
 * ```
 *
 * ## Test Workflow Example
 *
 * ```kotlin
 * test("should create user via API and store in Couchbase") {
 *     TestSystem.validate {
 *         // Setup: ensure clean state
 *         couchbase {
 *             shouldNotExist("user::new-user")
 *         }
 *
 *         // Action: create user via API
 *         http {
 *             postAndExpectBodilessResponse(
 *                 uri = "/users",
 *                 body = CreateUserRequest(name = "New User").some()
 *             ) { response ->
 *                 response.status shouldBe 201
 *             }
 *         }
 *
 *         // Assert: verify in Couchbase
 *         couchbase {
 *             shouldGet<User>("users", "user::new-user") { user ->
 *                 user.name shouldBe "New User"
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
 * TestSystem()
 *     .with {
 *         couchbase {
 *             CouchbaseSystemOptions(
 *                 defaultBucket = "my-bucket",
 *                 configureExposedConfiguration = { cfg ->
 *                     listOf(
 *                         "couchbase.connection-string=${cfg.connectionString}",
 *                         "couchbase.username=${cfg.username}",
 *                         "couchbase.password=${cfg.password}"
 *                     )
 *                 }
 *             )
 *         }
 *     }
 * ```
 *
 * @property testSystem The parent test system.
 * @property context Couchbase context containing bucket and options.
 * @see CouchbaseSystemOptions
 * @see CouchbaseExposedConfiguration
 */
@CouchbaseDsl
class CouchbaseSystem internal constructor(
  override val testSystem: TestSystem,
  val context: CouchbaseContext
) : PluggedSystem,
  RunAware,
  ExposesConfiguration,
  Reports {
  @PublishedApi
  internal lateinit var cluster: Cluster

  @PublishedApi
  internal lateinit var collection: Collection

  private lateinit var exposedConfiguration: CouchbaseExposedConfiguration
  private val logger: Logger = LoggerFactory.getLogger(javaClass)
  private val state: StateStorage<CouchbaseExposedConfiguration> =
    testSystem.options.createStateStorage<CouchbaseExposedConfiguration, CouchbaseSystem>()

  override suspend fun run() {
    exposedConfiguration = obtainExposedConfiguration()
    cluster = createCluster(exposedConfiguration)
    collection = cluster.bucket(context.bucket.name).defaultCollection()
    runMigrationsIfNeeded()
  }

  override suspend fun stop(): Unit = whenContainer { it.stop() }

  override fun configuration(): List<String> = context.options.configureExposedConfiguration(exposedConfiguration)

  override fun close(): Unit = runBlocking {
    Try {
      context.options.cleanup(cluster)
      cluster.disconnect()
      executeWithReuseCheck { stop() }
    }.recover {
      logger.warn("Disconnecting the couchbase cluster got an error: $it")
    }
  }

  @CouchbaseDsl
  suspend inline fun <reified T : Any> shouldQuery(
    query: String,
    crossinline assertion: (List<T>) -> Unit
  ): CouchbaseSystem {
    val typeRef = typeRef<T>()
    val results = flow {
      cluster
        .query(
          statement = query,
          metrics = false,
          consistency = QueryScanConsistency.requestPlus(),
          serializer = context.options.clusterSerDe
        ).execute { row -> emit(context.options.clusterSerDe.deserialize(row.content, typeRef)) }
    }.toList()

    recordAndExecute(
      action = "N1QL Query",
      input = arrow.core.Some(query),
      output = arrow.core.Some("${results.size} document(s)"),
      actual = arrow.core.Some(results)
    ) {
      assertion(results)
    }

    return this
  }

  @CouchbaseDsl
  suspend inline fun <reified T : Any> shouldGet(
    key: String,
    crossinline assertion: (T) -> Unit
  ): CouchbaseSystem {
    val document = collection.get(key).contentAs<T>()
    recordAndExecute(
      action = "Get document",
      input = arrow.core.Some(mapOf("id" to key)),
      output = arrow.core.Some(document),
      actual = arrow.core.Some(document)
    ) {
      assertion(document)
    }
    return this
  }

  @CouchbaseDsl
  suspend inline fun <reified T : Any> shouldGet(
    collection: String,
    key: String,
    crossinline assertion: (T) -> Unit
  ): CouchbaseSystem {
    val document = cluster
      .bucket(context.bucket.name)
      .collection(collection)
      .get(key)
      .contentAs<T>()
    recordAndExecute(
      action = "Get document",
      input = arrow.core.Some(mapOf("collection" to collection, "id" to key)),
      output = arrow.core.Some(document),
      actual = arrow.core.Some(document)
    ) {
      assertion(document)
    }
    return this
  }

  @CouchbaseDsl
  suspend fun shouldNotExist(key: String): CouchbaseSystem {
    val exists = collection.getOrNull(key) != null
    recordAndExecute(
      action = "Document should not exist",
      input = arrow.core.Some(mapOf("id" to key)),
      expected = arrow.core.Some("Document not found"),
      actual = arrow.core.Some(if (exists) "Document exists" else "Document not found")
    ) {
      if (exists) throw AssertionError("The document with the given id($key) was not expected, but found!")
    }
    return this
  }

  @CouchbaseDsl
  suspend fun shouldNotExist(
    collection: String,
    key: String
  ): CouchbaseSystem {
    val exists = cluster
      .bucket(context.bucket.name)
      .collection(collection)
      .getOrNull(key) != null
    recordAndExecute(
      action = "Document should not exist",
      input = arrow.core.Some(mapOf("collection" to collection, "id" to key)),
      expected = arrow.core.Some("Document not found"),
      actual = arrow.core.Some(if (exists) "Document exists" else "Document not found")
    ) {
      if (exists) throw AssertionError("The document with the given id($key) was not expected, but found!")
    }
    return this
  }

  @CouchbaseDsl
  suspend fun shouldDelete(key: String): CouchbaseSystem {
    collection.remove(key)
    recordSuccess(
      action = "Delete document",
      input = arrow.core.Some(mapOf("id" to key)),
      output = arrow.core.Some("Document deleted")
    )
    return this
  }

  @CouchbaseDsl
  suspend fun shouldDelete(
    collection: String,
    key: String
  ): CouchbaseSystem {
    cluster
      .bucket(context.bucket.name)
      .collection(collection)
      .remove(key)
    recordSuccess(
      action = "Delete document",
      input = arrow.core.Some(mapOf("collection" to collection, "id" to key)),
      output = arrow.core.Some("Document deleted")
    )
    return this
  }

  /**
   * Saves the [instance] with given [id] to the [collection]
   * To save to the default collection use [saveToDefaultCollection]
   */
  @CouchbaseDsl
  suspend inline fun <reified T : Any> save(
    collection: String,
    id: String,
    instance: T
  ): CouchbaseSystem {
    cluster.bucket(context.bucket.name).collection(collection).insert(id, instance)

    recordSuccess(
      action = "Save document",
      input = arrow.core.Some(instance),
      metadata = mapOf("collection" to collection, "id" to id)
    )

    return this
  }

  /**
   * Saves the [instance] with given [id] to the default collection
   * In couchbase the default collection is `_default`
   */
  @CouchbaseDsl
  suspend inline fun <reified T : Any> saveToDefaultCollection(
    id: String,
    instance: T
  ): CouchbaseSystem = this.save("_default", id, instance)

  /**
   * Pauses the container. Use with care, as it will pause the container which might affect other tests.
   * This operation is not supported when using a provided instance.
   * @return CouchbaseSystem
   */
  @CouchbaseDsl
  fun pause(): CouchbaseSystem = withContainerOrWarn("pause") { it.pause() }

  /**
   * Unpauses the container. Use with care, as it will unpause the container which might affect other tests.
   * This operation is not supported when using a provided instance.
   * @return CouchbaseSystem
   */
  @CouchbaseDsl
  fun unpause(): CouchbaseSystem = withContainerOrWarn("unpause") { it.unpause() }

  private suspend fun obtainExposedConfiguration(): CouchbaseExposedConfiguration =
    when {
      context.options is ProvidedCouchbaseSystemOptions -> context.options.config
      context.runtime is StoveCouchbaseContainer -> startCouchbaseContainer(context.runtime)
      else -> throw UnsupportedOperationException("Unsupported runtime type: ${context.runtime::class}")
    }

  private suspend fun startCouchbaseContainer(container: StoveCouchbaseContainer): CouchbaseExposedConfiguration =
    state.capture {
      container.start()
      CouchbaseExposedConfiguration(
        connectionString = container.connectionString,
        hostsWithPort = container.connectionString.replace("couchbase://", ""),
        username = container.username,
        password = container.password
      )
    }

  private suspend fun runMigrationsIfNeeded() {
    if (shouldRunMigrations()) {
      context.options.migrationCollection.run(cluster)
    }
  }

  private fun shouldRunMigrations(): Boolean = when {
    context.options is ProvidedCouchbaseSystemOptions -> context.options.runMigrations
    context.runtime is StoveCouchbaseContainer -> !state.isSubsequentRun() || testSystem.options.runMigrationsAlways
    else -> throw UnsupportedOperationException("Unsupported runtime type: ${context.runtime::class}")
  }

  private fun createCluster(exposedConfiguration: CouchbaseExposedConfiguration): Cluster = Cluster.connect(
    exposedConfiguration.hostsWithPort,
    exposedConfiguration.username,
    exposedConfiguration.password
  ) {
    jsonSerializer = context.options.clusterSerDe
    transcoder = context.options.clusterTranscoder
  }

  private inline fun withContainerOrWarn(
    operation: String,
    action: (StoveCouchbaseContainer) -> Unit
  ): CouchbaseSystem = when (val runtime = context.runtime) {
    is StoveCouchbaseContainer -> {
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

  private inline fun whenContainer(action: (StoveCouchbaseContainer) -> Unit) {
    if (context.runtime is StoveCouchbaseContainer) {
      action(context.runtime)
    }
  }

  companion object {
    /**
     * Exposes the [Cluster] to the [CouchbaseSystem].
     * Use this for advanced Couchbase operations not covered by the DSL.
     */
    @CouchbaseDsl
    fun CouchbaseSystem.cluster(): Cluster {
      recordSuccess(action = "Access underlying Couchbase Cluster")
      return this.cluster
    }

    /**
     * Exposes the [Bucket] to the [CouchbaseSystem].
     * Use this for advanced Couchbase operations not covered by the DSL.
     */
    @CouchbaseDsl
    fun CouchbaseSystem.bucket(): Bucket {
      recordSuccess(action = "Access underlying Couchbase Bucket")
      return this.cluster.bucket(this.context.bucket.name)
    }
  }
}
