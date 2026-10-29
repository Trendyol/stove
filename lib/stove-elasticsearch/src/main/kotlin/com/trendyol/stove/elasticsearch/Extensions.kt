package com.trendyol.stove.elasticsearch

import arrow.core.getOrElse
import com.trendyol.stove.chaos.*
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

internal fun Stove.withElasticsearch(
  key: SystemKey,
  options: ElasticsearchSystemOptions,
  runtime: SystemRuntime
): Stove {
  getOrRegister(key, ElasticsearchSystem(this, ElasticsearchContext(runtime, options, keyName = keyDisplayName(key))))
  return this
}

internal fun Stove.elasticsearch(): ElasticsearchSystem =
  getOrNone<ElasticsearchSystem>().getOrElse {
    throw SystemNotRegisteredException(ElasticsearchSystem::class)
  }

internal fun Stove.elasticsearch(key: SystemKey): ElasticsearchSystem =
  getOrNone<ElasticsearchSystem>(key).getOrElse {
    throw SystemNotRegisteredException(ElasticsearchSystem::class, "No ElasticsearchSystem registered with key '${keyDisplayName(key)}'")
  }

/** Registers Elasticsearch; call enableChaos() to route application configuration through a managed TCP proxy. */
fun WithDsl.elasticsearch(configure: @StoveDsl ChaosSetupScope.() -> ElasticsearchSystemOptions): Stove =
  configureElasticsearch(null, configure)

fun WithDsl.elasticsearch(key: SystemKey, configure: @StoveDsl ChaosSetupScope.() -> ElasticsearchSystemOptions): Stove =
  configureElasticsearch(key, configure)

private fun WithDsl.configureElasticsearch(key: SystemKey?, configure: ChaosSetupScope.() -> ElasticsearchSystemOptions): Stove {
  val scope = ChaosSetupScope()
  val options = configure(scope)
  val runtime = options.createRuntime(stove.keepDependenciesRunning)
  if (key == null) stove.withElasticsearch(options, runtime) else stove.withElasticsearch(key, options, runtime)
  if (scope.chaosEnabled) {
    val system = if (key == null) stove.elasticsearch() else stove.elasticsearch(key)
    system.enableChaosRouting()
  }
  return stove
}

private fun ElasticsearchSystemOptions.createRuntime(keepDependenciesRunning: Boolean): SystemRuntime {
  if (this is ProvidedElasticsearchSystemOptions) return ProvidedRuntime
  return withProvidedRegistry(
    imageName = container.imageWithTag,
    registry = container.registry,
    compatibleSubstitute = container.compatibleSubstitute
  ) { dockerImageName -> StoveElasticSearchContainer(dockerImageName) }.apply {
    addExposedPorts(*container.exposedPorts.toIntArray())
    withPassword(container.password)
    if (container.disableSecurity) withEnv("xpack.security.enabled", "false")
    withReuse(keepDependenciesRunning)
    container.containerFn(this)
  }
}

suspend fun ValidationDsl.elasticsearch(validation: @ElasticDsl suspend ElasticsearchSystem.() -> Unit): Unit =
  withSystemValidation(this.stove.elasticsearch(), validation)

suspend fun ValidationDsl.elasticsearch(key: SystemKey, validation: @ElasticDsl suspend ElasticsearchSystem.() -> Unit): Unit =
  withSystemValidation(this.stove.elasticsearch(key), validation)
