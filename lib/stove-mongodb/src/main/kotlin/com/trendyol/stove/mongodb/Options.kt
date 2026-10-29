package com.trendyol.stove.mongodb

import arrow.core.getOrElse
import com.trendyol.stove.chaos.*
import com.trendyol.stove.containers.*
import com.trendyol.stove.system.*
import com.trendyol.stove.system.abstractions.*
import com.trendyol.stove.system.annotations.StoveDsl
import org.testcontainers.mongodb.MongoDBContainer
import org.testcontainers.utility.DockerImageName

@StoveDsl
data class MongodbExposedConfiguration(
  val connectionString: String,
  val host: String,
  val port: Int,
  val replicaSetUrl: String
) : ExposedConfiguration

@StoveDsl
data class MongodbContext(
  val runtime: SystemRuntime,
  val options: MongodbSystemOptions,
  val keyName: String? = null
)

open class StoveMongoContainer(
  override val imageNameAccess: DockerImageName
) : MongoDBContainer(imageNameAccess),
  StoveContainer

@StoveDsl
data class MongoContainerOptions(
  override val registry: String = DEFAULT_REGISTRY,
  override val image: String = "mongo",
  override val tag: String = "latest",
  override val compatibleSubstitute: String? = null,
  override val useContainerFn: UseContainerFn<StoveMongoContainer> = { StoveMongoContainer(it) },
  override val containerFn: ContainerFn<StoveMongoContainer> = { }
) : ContainerOptions<StoveMongoContainer>

@StoveDsl
data class DatabaseOptions(
  val default: DefaultDatabase = DefaultDatabase()
) {
  data class DefaultDatabase(
    val name: String = "stove",
    val collection: String = "stoveCollection"
  )
}

internal fun Stove.withMongodb(
  options: MongodbSystemOptions,
  runtime: SystemRuntime
): Stove {
  getOrRegister(MongodbSystem(this, MongodbContext(runtime, options)))
  return this
}

internal fun Stove.withMongodb(
  key: SystemKey,
  options: MongodbSystemOptions,
  runtime: SystemRuntime
): Stove {
  getOrRegister(key, MongodbSystem(this, MongodbContext(runtime, options, keyName = keyDisplayName(key))))
  return this
}

internal fun Stove.mongodb(): MongodbSystem =
  getOrNone<MongodbSystem>().getOrElse {
    throw SystemNotRegisteredException(MongodbSystem::class)
  }

internal fun Stove.mongodb(key: SystemKey): MongodbSystem =
  getOrNone<MongodbSystem>(key).getOrElse {
    throw SystemNotRegisteredException(MongodbSystem::class, "No MongodbSystem registered with key '${keyDisplayName(key)}'")
  }

/**
 * Configures MongoDB system.
 *
 * For container-based setup:
 * ```kotlin
 * mongodb {
 *   MongodbSystemOptions(
 *     cleanup = { client -> client.getDatabase("mydb").drop() },
 *     configureExposedConfiguration = { cfg -> listOf(...) }
 *   )
 * }
 * ```
 *
 * For provided (external) instance:
 * ```kotlin
 * mongodb {
 *   MongodbSystemOptions.provided(
 *     connectionString = "mongodb://localhost:27017",
 *     host = "localhost",
 *     port = 27017,
 *     cleanup = { client -> client.getDatabase("mydb").drop() },
 *     configureExposedConfiguration = { cfg -> listOf(...) }
 *   )
 * }
 * ```
 */

/**
 * Registers MongoDB. enableChaos() routes the application through one managed TCP endpoint and
 * forces directConnection=true. SRV, multiple seeds, load balancing and TLS are unsupported.
 * Use the exposed connectionString or replicaSetUrl; rebuilding a URI from host/port loses direct mode.
 */
fun WithDsl.mongodb(configure: @StoveDsl ChaosSetupScope.() -> MongodbSystemOptions): Stove =
  configureMongodb(null, configure)

fun WithDsl.mongodb(key: SystemKey, configure: @StoveDsl ChaosSetupScope.() -> MongodbSystemOptions): Stove =
  configureMongodb(key, configure)

private fun WithDsl.configureMongodb(key: SystemKey?, configure: ChaosSetupScope.() -> MongodbSystemOptions): Stove {
  val scope = ChaosSetupScope()
  val options = configure(scope)
  if (scope.chaosEnabled && options is ProvidedMongodbSystemOptions) MongodbChaosAdapter.endpoint(options.config)
  val runtime = options.createRuntime(stove.keepDependenciesRunning)
  if (key == null) stove.withMongodb(options, runtime) else stove.withMongodb(key, options, runtime)
  if (scope.chaosEnabled) {
    val system = if (key == null) stove.mongodb() else stove.mongodb(key)
    system.enableChaosRouting()
  }
  return stove
}

private fun MongodbSystemOptions.createRuntime(keepDependenciesRunning: Boolean): SystemRuntime {
  if (this is ProvidedMongodbSystemOptions) return ProvidedRuntime
  return withProvidedRegistry(
    container.imageWithTag,
    container.registry,
    container.compatibleSubstitute
  ) { dockerImageName ->
    container
      .useContainerFn(dockerImageName)
      .withReuse(keepDependenciesRunning)
      .let { c -> c as StoveMongoContainer }
      .apply(container.containerFn)
  }
}

suspend fun ValidationDsl.mongodb(
  validation: @MongoDsl suspend MongodbSystem.() -> Unit
): Unit = withSystemValidation(this.stove.mongodb(), validation)

suspend fun ValidationDsl.mongodb(
  key: SystemKey,
  validation: @MongoDsl suspend MongodbSystem.() -> Unit
): Unit = withSystemValidation(this.stove.mongodb(key), validation)
