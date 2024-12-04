package com.trendyol.stove.testing.e2e.elasticsearch

import arrow.core.getOrElse
import com.trendyol.stove.testing.e2e.containers.withProvidedRegistry
import com.trendyol.stove.testing.e2e.system.*
import com.trendyol.stove.testing.e2e.system.abstractions.SystemNotRegisteredException
import com.trendyol.stove.testing.e2e.system.annotations.StoveDsl

/**
 * Integrates Elasticsearch with the TestSystem.
 *
 * Provides an [options] class to configure the Elasticsearch container.
 * You can configure it by changing the implementation of migrator.
 */
internal fun TestSystem.withElasticsearch(options: ElasticsearchSystemOptions): TestSystem = withProvidedRegistry(
  imageName = options.container.imageWithTag,
  registry = options.container.registry,
  compatibleSubstitute = options.container.compatibleSubstitute
) { StoveElasticSearchContainer(it) }
  .apply {
    addExposedPorts(*options.container.exposedPorts.toIntArray())
    withPassword(options.container.password)
    if (options.container.disableSecurity) {
      withEnv("xpack.security.enabled", "false")
    }
    withReuse(this@withElasticsearch.options.keepDependenciesRunning)
    options.container.containerFn(this)
  }.let { getOrRegister(ElasticsearchSystem(this, ElasticsearchContext(it, options))) }
  .let { this }

internal fun TestSystem.elasticsearch(): ElasticsearchSystem =
  getOrNone<ElasticsearchSystem>().getOrElse {
    throw SystemNotRegisteredException(ElasticsearchSystem::class)
  }

@StoveDsl
fun WithDsl.elasticsearch(
  configure: @StoveDsl () -> ElasticsearchSystemOptions
): TestSystem = this.testSystem.withElasticsearch(configure())

@StoveDsl
suspend fun ValidationDsl.elasticsearch(validation: @ElasticDsl suspend ElasticsearchSystem.() -> Unit): Unit =
  validation(this.testSystem.elasticsearch())
