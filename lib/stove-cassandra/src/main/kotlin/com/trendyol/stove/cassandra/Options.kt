@file:Suppress("unused")

package com.trendyol.stove.cassandra

import arrow.core.getOrElse
import com.trendyol.stove.chaos.*
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

/**
 * Enables application-link faults for a dedicated single-node container when enableChaos() is called.
 * The application must use the exposed contact point as its only seed. Cluster discovery, topology
 * changes, provided clusters and alternate contact points are unsupported.
 */
fun WithDsl.cassandra(configure: @StoveDsl ChaosSetupScope.() -> CassandraSystemOptions): Stove =
  configureCassandra(null, configure)

fun WithDsl.cassandra(key: SystemKey, configure: @StoveDsl ChaosSetupScope.() -> CassandraSystemOptions): Stove =
  configureCassandra(key, configure)

private fun WithDsl.configureCassandra(key: SystemKey?, configure: ChaosSetupScope.() -> CassandraSystemOptions): Stove {
  val scope = ChaosSetupScope()
  val options = configure(scope)
  require(!scope.chaosEnabled || options !is ProvidedCassandraSystemOptions) {
    "Cassandra chaos supports only a dedicated single-node container; provided clusters can advertise unproxied peers"
  }
  val runtime = options.createRuntime(stove.keepDependenciesRunning)
  if (key == null) stove.withCassandra(options, runtime) else stove.withCassandra(key, options, runtime)
  if (scope.chaosEnabled) {
    val system = if (key == null) stove.cassandra() else stove.cassandra(key)
    system.enableChaosRouting()
  }
  return stove
}

private fun CassandraSystemOptions.createRuntime(keepDependenciesRunning: Boolean): SystemRuntime {
  if (this is ProvidedCassandraSystemOptions) return ProvidedRuntime
  return withProvidedRegistry(
    container.imageWithTag,
    container.registry,
    container.compatibleSubstitute
  ) { dockerImageName ->
    container
      .useContainerFn(dockerImageName)
      .withReuse(keepDependenciesRunning)
      .let { c -> c as StoveCassandraContainer }
      .apply(container.containerFn)
  }
}

suspend fun ValidationDsl.cassandra(
  validation: @CassandraDsl suspend CassandraSystem.() -> Unit
): Unit = withSystemValidation(this.stove.cassandra(), validation)

suspend fun ValidationDsl.cassandra(
  key: SystemKey,
  validation: @CassandraDsl suspend CassandraSystem.() -> Unit
): Unit = withSystemValidation(this.stove.cassandra(key), validation)
