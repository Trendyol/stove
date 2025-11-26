package com.trendyol.stove.testing.e2e.elasticsearch

import arrow.core.getOrElse
import com.trendyol.stove.testing.e2e.containers.withProvidedRegistry
import com.trendyol.stove.testing.e2e.system.*
import com.trendyol.stove.testing.e2e.system.abstractions.*
import com.trendyol.stove.testing.e2e.system.annotations.StoveDsl

internal fun TestSystem.withElasticsearch(
  options: ElasticsearchSystemOptions,
  runtime: SystemRuntime
): TestSystem {
  getOrRegister(ElasticsearchSystem(this, ElasticsearchContext(runtime, options)))
  return this
}

internal fun TestSystem.elasticsearch(): ElasticsearchSystem =
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
 *     cleanup = { es -> es.deleteIndex("my-index") },
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
 *     cleanup = { es -> es.deleteIndex("my-index") },
 *     configureExposedConfiguration = { cfg -> listOf(...) }
 *   )
 * }
 * ```
 */
@StoveDsl
fun WithDsl.elasticsearch(
  configure: @StoveDsl () -> ElasticsearchSystemOptions
): TestSystem {
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
        withReuse(testSystem.options.keepDependenciesRunning)
        options.container.containerFn(this)
      }
  }
  return testSystem.withElasticsearch(options, runtime)
}

@StoveDsl
suspend fun ValidationDsl.elasticsearch(validation: @ElasticDsl suspend ElasticsearchSystem.() -> Unit): Unit =
  validation(this.testSystem.elasticsearch())
