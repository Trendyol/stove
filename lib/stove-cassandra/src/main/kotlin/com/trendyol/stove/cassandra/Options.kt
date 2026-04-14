@file:Suppress("unused")

package com.trendyol.stove.cassandra

import arrow.core.getOrElse
import com.trendyol.stove.containers.*
import com.trendyol.stove.system.*
import com.trendyol.stove.system.abstractions.*
import com.trendyol.stove.system.annotations.StoveDsl
import org.testcontainers.cassandra.CassandraContainer
import org.testcontainers.utility.DockerImageName

@StoveDsl
data class CassandraExposedConfiguration(
  val host: String,
  val port: Int,
  val datacenter: String,
  val keyspace: String
) : ExposedConfiguration

@StoveDsl
data class CassandraContext(
  val runtime: SystemRuntime,
  val options: CassandraSystemOptions,
  val keyName: String? = null
)

open class StoveCassandraContainer(
  override val imageNameAccess: DockerImageName
) : CassandraContainer(imageNameAccess),
  StoveContainer

@StoveDsl
data class CassandraContainerOptions(
  override val registry: String = DEFAULT_REGISTRY,
  override val image: String = "cassandra",
  override val tag: String = "4",
  override val compatibleSubstitute: String? = null,
  override val useContainerFn: UseContainerFn<StoveCassandraContainer> = { StoveCassandraContainer(it) },
  override val containerFn: ContainerFn<StoveCassandraContainer> = { }
) : ContainerOptions<StoveCassandraContainer>

internal fun Stove.withCassandra(
  options: CassandraSystemOptions,
  runtime: SystemRuntime
): Stove {
  getOrRegister(CassandraSystem(this, CassandraContext(runtime, options)))
  return this
}

internal fun Stove.withCassandra(
  key: SystemKey,
  options: CassandraSystemOptions,
  runtime: SystemRuntime
): Stove {
  getOrRegister(key, CassandraSystem(this, CassandraContext(runtime, options, keyName = keyDisplayName(key))))
  return this
}

internal fun Stove.cassandra(): CassandraSystem =
  getOrNone<CassandraSystem>().getOrElse {
    throw SystemNotRegisteredException(CassandraSystem::class)
  }

internal fun Stove.cassandra(key: SystemKey): CassandraSystem =
  getOrNone<CassandraSystem>(key).getOrElse {
    throw SystemNotRegisteredException(CassandraSystem::class, "No CassandraSystem registered with key '${keyDisplayName(key)}'")
  }

/**
 * Configures Cassandra system.
 *
 * For container-based setup:
 * ```kotlin
 * cassandra {
 *   CassandraSystemOptions(
 *     keyspace = "my_keyspace",
 *     cleanup = { session -> session.execute("TRUNCATE my_keyspace.my_table") },
 *     configureExposedConfiguration = { cfg -> listOf(...) }
 *   )
 * }
 * ```
 *
 * For provided (external) instance:
 * ```kotlin
 * cassandra {
 *   CassandraSystemOptions.provided(
 *     host = "localhost",
 *     port = 9042,
 *     datacenter = "datacenter1",
 *     keyspace = "my_keyspace",
 *     cleanup = { session -> session.execute("TRUNCATE my_keyspace.my_table") },
 *     configureExposedConfiguration = { cfg -> listOf(...) }
 *   )
 * }
 * ```
 */
fun WithDsl.cassandra(
  configure: () -> CassandraSystemOptions
): Stove {
  val options = configure()

  val runtime: SystemRuntime = if (options is ProvidedCassandraSystemOptions) {
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
        .let { c -> c as StoveCassandraContainer }
        .apply(options.container.containerFn)
    }
  }
  return stove.withCassandra(options, runtime)
}

fun WithDsl.cassandra(
  key: SystemKey,
  configure: () -> CassandraSystemOptions
): Stove {
  val options = configure()

  val runtime: SystemRuntime = if (options is ProvidedCassandraSystemOptions) {
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
        .let { c -> c as StoveCassandraContainer }
        .apply(options.container.containerFn)
    }
  }
  return stove.withCassandra(key, options, runtime)
}

suspend fun ValidationDsl.cassandra(
  validation: @CassandraDsl suspend CassandraSystem.() -> Unit
): Unit = validation(this.stove.cassandra())

suspend fun ValidationDsl.cassandra(
  key: SystemKey,
  validation: @CassandraDsl suspend CassandraSystem.() -> Unit
): Unit = validation(this.stove.cassandra(key))
