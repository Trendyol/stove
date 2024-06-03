package com.trendyol.stove.testing.e2e.kafka

import arrow.core.getOrElse
import com.fasterxml.jackson.databind.ObjectMapper
import com.trendyol.stove.testing.e2e.containers.*
import com.trendyol.stove.testing.e2e.serialization.StoveObjectMapper
import com.trendyol.stove.testing.e2e.system.*
import com.trendyol.stove.testing.e2e.system.abstractions.*
import com.trendyol.stove.testing.e2e.system.annotations.StoveDsl
import org.apache.kafka.clients.producer.ProducerRecord
import org.springframework.kafka.core.KafkaTemplate
import org.testcontainers.containers.KafkaContainer
import org.testcontainers.utility.DockerImageName

open class StoveKafkaContainer(
  override val imageNameAccess: DockerImageName
) : KafkaContainer(imageNameAccess), StoveContainer

@StoveDsl
data class KafkaExposedConfiguration(
  val bootstrapServers: String
) : ExposedConfiguration

@StoveDsl
data class KafkaContainerOptions(
  override val registry: String = DEFAULT_REGISTRY,
  override val image: String = "confluentinc/cp-kafka",
  override val tag: String = "latest",
  override val compatibleSubstitute: String? = null,
  override val useContainerFn: UseContainerFn<StoveKafkaContainer> = { StoveKafkaContainer(it) },
  override val containerFn: ContainerFn<StoveKafkaContainer> = { }
) : ContainerOptions<StoveKafkaContainer>

data class KafkaOps(
  val send: suspend (
    KafkaTemplate<*, *>,
    ProducerRecord<*, *>
  ) -> Unit = { kafkaTemplate, record -> kafkaTemplate.sendCompatible(record) }
)

@StoveDsl
data class KafkaSystemOptions(
  val registry: String = DEFAULT_REGISTRY,
  val ports: List<Int> = DEFAULT_KAFKA_PORTS,
  val objectMapper: ObjectMapper = StoveObjectMapper.Default,
  val containerOptions: KafkaContainerOptions = KafkaContainerOptions(),
  val ops: KafkaOps = KafkaOps(),
  override val configureExposedConfiguration: (KafkaExposedConfiguration) -> List<String> = { _ -> listOf() }
) : SystemOptions, ConfiguresExposedConfiguration<KafkaExposedConfiguration> {
  companion object {
    val DEFAULT_KAFKA_PORTS = listOf(9092, 9093)
  }
}

@StoveDsl
data class KafkaContext(
  val container: StoveKafkaContainer,
  val objectMapper: ObjectMapper,
  val options: KafkaSystemOptions
)

internal fun TestSystem.withKafka(options: KafkaSystemOptions = KafkaSystemOptions()): TestSystem =
  withProvidedRegistry(
    options.containerOptions.imageWithTag,
    registry = options.registry,
    compatibleSubstitute = options.containerOptions.compatibleSubstitute
  ) {
    options.containerOptions.useContainerFn(it)
      .withExposedPorts(*options.ports.toTypedArray())
      .withReuse(this.options.keepDependenciesRunning)
      .let { c -> c as StoveKafkaContainer }
      .apply(options.containerOptions.containerFn)
  }.let { getOrRegister(KafkaSystem(this, KafkaContext(it, options.objectMapper, options))) }
    .let { this }

internal fun TestSystem.kafka(): KafkaSystem = getOrNone<KafkaSystem>().getOrElse {
  throw SystemNotRegisteredException(KafkaSystem::class)
}

@StoveDsl
fun WithDsl.kafka(
  configure: () -> KafkaSystemOptions = { KafkaSystemOptions() }
): TestSystem = this.testSystem.withKafka(configure())

@StoveDsl
suspend fun ValidationDsl.kafka(validation: suspend KafkaSystem.() -> Unit): Unit = validation(this.testSystem.kafka())
