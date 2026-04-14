package com.trendyol.stove.kafka

import arrow.core.getOrElse
import com.trendyol.stove.containers.withProvidedRegistry
import com.trendyol.stove.system.*
import com.trendyol.stove.system.abstractions.*
import com.trendyol.stove.system.annotations.StoveDsl

data class KafkaContext(
  val runtime: SystemRuntime,
  val options: KafkaSystemOptions,
  val keyName: String? = null
)

internal fun Stove.kafka(): KafkaSystem = getOrNone<KafkaSystem>().getOrElse {
  throw SystemNotRegisteredException(KafkaSystem::class)
}

internal fun Stove.kafka(key: SystemKey): KafkaSystem = getOrNone<KafkaSystem>(key).getOrElse {
  throw SystemNotRegisteredException(KafkaSystem::class, "No KafkaSystem registered with key '${keyDisplayName(key)}'")
}

internal fun Stove.withKafka(
  options: KafkaSystemOptions,
  runtime: SystemRuntime
): Stove {
  getOrRegister(KafkaSystem(this, KafkaContext(runtime, options)))
  return this
}

internal fun Stove.withKafka(
  key: SystemKey,
  options: KafkaSystemOptions,
  runtime: SystemRuntime
): Stove {
  getOrRegister(key, KafkaSystem(this, KafkaContext(runtime, options, keyName = keyDisplayName(key))))
  return this
}

suspend fun ValidationDsl.kafka(
  validation: @StoveDsl suspend KafkaSystem.() -> Unit
): Unit = validation(this.stove.kafka())

suspend fun ValidationDsl.kafka(
  key: SystemKey,
  validation: @StoveDsl suspend KafkaSystem.() -> Unit
): Unit = validation(this.stove.kafka(key))

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
fun WithDsl.kafka(
  configure: () -> KafkaSystemOptions
): Stove {
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
        .withReuse(stove.keepDependenciesRunning)
        .let { c -> c as StoveKafkaContainer }
        .apply(options.containerOptions.containerFn)
    }
  }
  return stove.withKafka(options, runtime)
}

/**
 * Configures a keyed Kafka system for testing multiple Kafka instances.
 *
 * ```kotlin
 * Stove().with {
 *     kafka(PaymentKafka) {
 *         KafkaSystemOptions(
 *             configureExposedConfiguration = { cfg -> listOf(...) }
 *         )
 *     }
 * }
 * ```
 *
 * @param key The [SystemKey] identifying this Kafka instance.
 * @param configure Configuration block returning [KafkaSystemOptions].
 * @return The test system for fluent chaining.
 */
fun WithDsl.kafka(
  key: SystemKey,
  configure: () -> KafkaSystemOptions
): Stove {
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
        .withReuse(stove.keepDependenciesRunning)
        .let { c -> c as StoveKafkaContainer }
        .apply(options.containerOptions.containerFn)
    }
  }
  return stove.withKafka(key, options, runtime)
}

/**
 * Special runtime for embedded Kafka that doesn't use a container.
 */
data object EmbeddedKafkaRuntime : SystemRuntime
