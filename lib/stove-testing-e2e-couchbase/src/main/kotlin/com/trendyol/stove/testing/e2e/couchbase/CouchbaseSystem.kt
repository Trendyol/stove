@file:Suppress("unused")

package com.trendyol.stove.testing.e2e.couchbase

import com.couchbase.client.kotlin.*
import com.couchbase.client.kotlin.Collection
import com.couchbase.client.kotlin.codec.typeRef
import com.couchbase.client.kotlin.query.*
import com.trendyol.stove.functional.*
import com.trendyol.stove.testing.e2e.system.TestSystem
import com.trendyol.stove.testing.e2e.system.abstractions.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.runBlocking
import org.slf4j.*

@CouchbaseDsl
class CouchbaseSystem internal constructor(
  override val testSystem: TestSystem,
  val context: CouchbaseContext
) : PluggedSystem,
  RunAware,
  ExposesConfiguration {
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
    assertion: (List<T>) -> Unit
  ): CouchbaseSystem {
    val typeRef = typeRef<T>()
    return flow {
      cluster
        .query(
          statement = query,
          metrics = false,
          consistency = QueryScanConsistency.requestPlus(),
          serializer = context.options.clusterSerDe
        ).execute { row -> emit(context.options.clusterSerDe.deserialize(row.content, typeRef)) }
    }.toList().also(assertion).let { this }
  }

  @CouchbaseDsl
  suspend inline fun <reified T : Any> shouldGet(
    key: String,
    assertion: (T) -> Unit
  ): CouchbaseSystem =
    collection
      .get(key)
      .contentAs<T>()
      .let(assertion)
      .let { this }

  @CouchbaseDsl
  suspend inline fun <reified T : Any> shouldGet(
    collection: String,
    key: String,
    assertion: (T) -> Unit
  ): CouchbaseSystem =
    cluster
      .bucket(context.bucket.name)
      .collection(collection)
      .get(key)
      .contentAs<T>()
      .let(assertion)
      .let { this }

  @CouchbaseDsl
  suspend fun shouldNotExist(key: String): CouchbaseSystem = when (collection.getOrNull(key)) {
    null -> this
    else -> throw AssertionError("The document with the given id($key) was not expected, but found!")
  }

  @CouchbaseDsl
  suspend fun shouldNotExist(
    collection: String,
    key: String
  ): CouchbaseSystem = when (
    cluster
      .bucket(context.bucket.name)
      .collection(collection)
      .getOrNull(key)
  ) {
    null -> this
    else -> throw AssertionError("The document with the given id($key) was not expected, but found!")
  }

  @CouchbaseDsl
  suspend fun shouldDelete(key: String): CouchbaseSystem = collection.remove(key).let { this }

  @CouchbaseDsl
  suspend fun shouldDelete(
    collection: String,
    key: String
  ): CouchbaseSystem = cluster
    .bucket(context.bucket.name)
    .collection(collection)
    .remove(key)
    .let { this }

  /**
   * Saves the [instance] with given [id] to the [collection]
   * To save to the default collection use [saveToDefaultCollection]
   */
  @CouchbaseDsl
  suspend inline fun <reified T : Any> save(
    collection: String,
    id: String,
    instance: T
  ): CouchbaseSystem = cluster
    .bucket(context.bucket.name)
    .collection(collection)
    .insert(id, instance)
    .let { this }

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
    @CouchbaseDsl
    fun CouchbaseSystem.cluster(): Cluster = this.cluster

    @CouchbaseDsl
    fun CouchbaseSystem.bucket(): Bucket = this.cluster.bucket(this.context.bucket.name)
  }
}
