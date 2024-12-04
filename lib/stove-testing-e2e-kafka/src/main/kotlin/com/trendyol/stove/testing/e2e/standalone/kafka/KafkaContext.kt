package com.trendyol.stove.testing.e2e.standalone.kafka

import arrow.core.getOrElse
import com.trendyol.stove.testing.e2e.containers.withProvidedRegistry
import com.trendyol.stove.testing.e2e.system.*
import com.trendyol.stove.testing.e2e.system.abstractions.SystemNotRegisteredException
import com.trendyol.stove.testing.e2e.system.annotations.StoveDsl

data class KafkaContext(
  val container: StoveKafkaContainer,
  val options: KafkaSystemOptions
)

internal fun TestSystem.kafka(): KafkaSystem = getOrNone<KafkaSystem>().getOrElse {
  throw SystemNotRegisteredException(KafkaSystem::class)
}

internal fun TestSystem.withKafka(options: KafkaSystemOptions): TestSystem {
  val kafka = withProvidedRegistry(
    options.containerOptions.imageWithTag,
    options.containerOptions.registry,
    options.containerOptions.compatibleSubstitute
  ) {
    options.containerOptions
      .useContainerFn(it)
      .withExposedPorts(*options.containerOptions.ports.toTypedArray())
      .withReuse(this.options.keepDependenciesRunning)
      .let { c -> c as StoveKafkaContainer }
      .apply(options.containerOptions.containerFn)
  }
  getOrRegister(KafkaSystem(this, KafkaContext(kafka, options)))
  return this
}

@StoveDsl
suspend fun ValidationDsl.kafka(
  validation: @StoveDsl suspend KafkaSystem.() -> Unit
): Unit = validation(this.testSystem.kafka())

@StoveDsl
fun WithDsl.kafka(configure: () -> KafkaSystemOptions): TestSystem = this.testSystem.withKafka(configure())
