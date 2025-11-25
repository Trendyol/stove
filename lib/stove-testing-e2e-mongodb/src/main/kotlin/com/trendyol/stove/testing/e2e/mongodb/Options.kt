package com.trendyol.stove.testing.e2e.mongodb

import arrow.core.getOrElse
import com.trendyol.stove.testing.e2e.containers.*
import com.trendyol.stove.testing.e2e.system.*
import com.trendyol.stove.testing.e2e.system.abstractions.*
import com.trendyol.stove.testing.e2e.system.annotations.StoveDsl
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
  val options: MongodbSystemOptions
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

internal fun TestSystem.withMongodb(
  options: MongodbSystemOptions,
  runtime: SystemRuntime
): TestSystem {
  getOrRegister(MongodbSystem(this, MongodbContext(runtime, options)))
  return this
}

internal fun TestSystem.mongodb(): MongodbSystem =
  getOrNone<MongodbSystem>().getOrElse {
    throw SystemNotRegisteredException(MongodbSystem::class)
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
@StoveDsl
fun WithDsl.mongodb(
  configure: () -> MongodbSystemOptions
): TestSystem {
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
        .withReuse(testSystem.options.keepDependenciesRunning)
        .let { c -> c as StoveMongoContainer }
        .apply(options.container.containerFn)
    }
  }
  return testSystem.withMongodb(options, runtime)
}

@StoveDsl
suspend fun ValidationDsl.mongodb(
  validation: @MongoDsl suspend MongodbSystem.() -> Unit
): Unit = validation(this.testSystem.mongodb())
