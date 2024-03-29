package com.trendyol.stove.testing.e2e.elasticsearch

import arrow.core.getOrElse
import arrow.integrations.jackson.module.registerArrowModule
import com.trendyol.stove.testing.e2e.containers.withProvidedRegistry
import com.trendyol.stove.testing.e2e.system.*
import com.trendyol.stove.testing.e2e.system.abstractions.SystemNotRegisteredException
import com.trendyol.stove.testing.e2e.system.annotations.StoveDsl
import org.testcontainers.elasticsearch.ElasticsearchContainer

/**
 * Integrates Elasticsearch with the TestSystem.
 *
 * Provides an [options] class to define [DefaultIndex] parameter to create an index as default index. You can configure it by changing the implementation of migrator.
 */
internal fun TestSystem.withElasticsearch(options: ElasticsearchSystemOptions): TestSystem {
    options.migrations {
        register<DefaultIndexMigrator> { options.defaultIndex.migrator }
    }

    options.objectMapper.registerArrowModule()

    return withProvidedRegistry(
        imageName = options.container.imageWithTag,
        registry = options.container.registry,
        compatibleSubstitute = options.container.compatibleSubstitute
    ) { ElasticsearchContainer(it) }
        .apply {
            addExposedPorts(*options.container.exposedPorts.toIntArray())
            withPassword(options.container.password)
            if (options.container.disableSecurity) {
                withEnv("xpack.security.enabled", "false")
            }
            withReuse(this@withElasticsearch.options.keepDependenciesRunning)
            options.container.containerFn(this)
        }
        .let { getOrRegister(ElasticsearchSystem(this, ElasticsearchContext(options.defaultIndex.index, it, options))) }
        .let { this }
}

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
