package com.trendyol.stove.elasticsearch

import arrow.core.getOrElse
import com.trendyol.stove.containers.withProvidedRegistry
import com.trendyol.stove.system.*
import com.trendyol.stove.system.abstractions.*
import com.trendyol.stove.system.annotations.StoveDsl

internal fun Stove.withElasticsearch(
  options: ElasticsearchSystemOptions,
  runtime: SystemRuntime
): Stove {
  getOrRegister(ElasticsearchSystem(this, ElasticsearchContext(runtime, options)))
  return this
}

internal fun Stove.elasticsearch(): ElasticsearchSystem =
  getOrNone<ElasticsearchSystem>().getOrElse {
    throw SystemNotRegisteredException(ElasticsearchSystem::class)
  }

/**
 * Configures Elasticsearch system.
 *
 * For container-based setup:
 * ```kotlin
 * elasticsearch {
 *   ElasticsearchSystemOptions(
 *     cleanup = { client -> client.indices().delete { it.index("*") } },
 *     configureExposedConfiguration = { cfg -> listOf(...) }
 *   )
 * }
 * ```
 *
 * For provided (external) instance:
 * ```kotlin
 * elasticsearch {
 *   ElasticsearchSystemOptions.provided(
 *     host = "localhost",
 *     port = 9200,
 *     password = "password",
 *     runMigrations = true,
 *     cleanup = { client -> client.indices().delete { it.index("*") } },
 *     configureExposedConfiguration = { cfg -> listOf(...) }
 *   )
 * }
 * ```
 */
@StoveDsl
fun WithDsl.elasticsearch(
  configure: @StoveDsl () -> ElasticsearchSystemOptions
): Stove {
  val options = configure()

  val runtime: SystemRuntime = if (options is ProvidedElasticsearchSystemOptions) {
    ProvidedRuntime
  } else {
    withProvidedRegistry(
      imageName = options.container.imageWithTag,
      registry = options.container.registry,
      compatibleSubstitute = options.container.compatibleSubstitute
    ) { dockerImageName -> StoveElasticSearchContainer(dockerImageName) }
      .apply {
        addExposedPorts(*options.container.exposedPorts.toIntArray())
        withPassword(options.container.password)
        if (options.container.disableSecurity) {
          withEnv("xpack.security.enabled", "false")
        }
        withReuse(stove.options.keepDependenciesRunning)
        options.container.containerFn(this)
      }
  }
  return stove.withElasticsearch(options, runtime)
}

@StoveDsl
suspend fun ValidationDsl.elasticsearch(validation: @ElasticDsl suspend ElasticsearchSystem.() -> Unit): Unit =
  validation(this.stove.elasticsearch())
