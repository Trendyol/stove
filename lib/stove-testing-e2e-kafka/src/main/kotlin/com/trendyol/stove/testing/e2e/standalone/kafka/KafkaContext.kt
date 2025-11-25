package com.trendyol.stove.testing.e2e.standalone.kafka

import arrow.core.getOrElse
import com.trendyol.stove.testing.e2e.containers.withProvidedRegistry
import com.trendyol.stove.testing.e2e.system.*
import com.trendyol.stove.testing.e2e.system.abstractions.*
import com.trendyol.stove.testing.e2e.system.annotations.StoveDsl

data class KafkaContext(
  val runtime: SystemRuntime,
  val options: KafkaSystemOptions
)

internal fun TestSystem.kafka(): KafkaSystem = getOrNone<KafkaSystem>().getOrElse {
  throw SystemNotRegisteredException(KafkaSystem::class)
}

internal fun TestSystem.withKafka(
  options: KafkaSystemOptions,
  runtime: SystemRuntime
): TestSystem {
  getOrRegister(KafkaSystem(this, KafkaContext(runtime, options)))
  return this
}

@StoveDsl
suspend fun ValidationDsl.kafka(
  validation: @StoveDsl suspend KafkaSystem.() -> Unit
): Unit = validation(this.testSystem.kafka())

/**
 * Configures Kafka system.
 *
 * For container-based setup:
 * ```kotlin
 * kafka {
 *   KafkaSystemOptions(
 *     cleanup = { admin -> admin.deleteTopics(...) },
 *     configureExposedConfiguration = { cfg -> listOf(...) }
 *   )
 * }
 * ```
 *
 * For embedded Kafka:
 * ```kotlin
 * kafka {
 *   KafkaSystemOptions(
 *     useEmbeddedKafka = true,
 *     cleanup = { admin -> admin.deleteTopics(...) },
 *     configureExposedConfiguration = { cfg -> listOf(...) }
 *   )
 * }
 * ```
 *
 * For provided (external) instance:
 * ```kotlin
 * kafka {
 *   KafkaSystemOptions.provided(
 *     bootstrapServers = "localhost:9092",
 *     cleanup = { admin -> admin.deleteTopics(...) },
 *     configureExposedConfiguration = { cfg -> listOf(...) }
 *   )
 * }
 * ```
 */
@StoveDsl
fun WithDsl.kafka(
  configure: () -> KafkaSystemOptions
): TestSystem {
  val options = configure()

  val runtime: SystemRuntime = when {
    options is ProvidedKafkaSystemOptions -> ProvidedRuntime

    options.useEmbeddedKafka -> EmbeddedKafkaRuntime

    else -> withProvidedRegistry(
      options.containerOptions.imageWithTag,
      options.containerOptions.registry,
      options.containerOptions.compatibleSubstitute
    ) { dockerImageName ->
      options.containerOptions
        .useContainerFn(dockerImageName)
        .withExposedPorts(*options.containerOptions.ports.toTypedArray())
        .withReuse(testSystem.options.keepDependenciesRunning)
        .let { c -> c as StoveKafkaContainer }
        .apply(options.containerOptions.containerFn)
    }
  }
  return testSystem.withKafka(options, runtime)
}

/**
 * Special runtime for embedded Kafka that doesn't use a container.
 */
data object EmbeddedKafkaRuntime : SystemRuntime
