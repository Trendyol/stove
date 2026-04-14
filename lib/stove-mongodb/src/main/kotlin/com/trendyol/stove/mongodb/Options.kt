package com.trendyol.stove.mongodb

import arrow.core.getOrElse
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
fun WithDsl.mongodb(
  configure: () -> MongodbSystemOptions
): Stove {
  val options = configure()

  val runtime: SystemRuntime = if (options is ProvidedMongodbSystemOptions) {
    ProvidedRuntime
  } else {
    withProvidedRegistry(
      options.container.imageWithTag,
      options.container.registry,
      options.container.compatibleSubstitute
    ) { dockerImageName ->
      options.container
        .useContainerFn(dockerImageName)
        .withReuse(stove.keepDependenciesRunning)
        .let { c -> c as StoveMongoContainer }
        .apply(options.container.containerFn)
    }
  }
  return stove.withMongodb(options, runtime)
}

fun WithDsl.mongodb(
  key: SystemKey,
  configure: () -> MongodbSystemOptions
): Stove {
  val options = configure()

  val runtime: SystemRuntime = if (options is ProvidedMongodbSystemOptions) {
    ProvidedRuntime
  } else {
    withProvidedRegistry(
      options.container.imageWithTag,
      options.container.registry,
      options.container.compatibleSubstitute
    ) { dockerImageName ->
      options.container
        .useContainerFn(dockerImageName)
        .withReuse(stove.keepDependenciesRunning)
        .let { c -> c as StoveMongoContainer }
        .apply(options.container.containerFn)
    }
  }
  return stove.withMongodb(key, options, runtime)
}

suspend fun ValidationDsl.mongodb(
  validation: @MongoDsl suspend MongodbSystem.() -> Unit
): Unit = validation(this.stove.mongodb())

suspend fun ValidationDsl.mongodb(
  key: SystemKey,
  validation: @MongoDsl suspend MongodbSystem.() -> Unit
): Unit = validation(this.stove.mongodb(key))
