package com.trendyol.stove.testing.e2e.kafka

import arrow.core.getOrElse
import com.fasterxml.jackson.databind.ObjectMapper
import com.trendyol.stove.testing.e2e.containers.ContainerOptions
import com.trendyol.stove.testing.e2e.containers.DEFAULT_REGISTRY
import com.trendyol.stove.testing.e2e.containers.withProvidedRegistry
import com.trendyol.stove.testing.e2e.serialization.StoveObjectMapper
import com.trendyol.stove.testing.e2e.system.TestSystem
import com.trendyol.stove.testing.e2e.system.ValidationDsl
import com.trendyol.stove.testing.e2e.system.WithDsl
import com.trendyol.stove.testing.e2e.system.abstractions.*
import org.testcontainers.containers.KafkaContainer

data class KafkaExposedConfiguration(
    val bootstrapServers: String
) : ExposedConfiguration

data class KafkaContainerOptions(
    override val registry: String = DEFAULT_REGISTRY,
    override val image: String = "confluentinc/cp-kafka",
    override val tag: String = "latest",
    override val imageWithTag: String = "$image:$tag"
) : ContainerOptions

data class KafkaSystemOptions(
    val registry: String = DEFAULT_REGISTRY,
    val ports: List<Int> = listOf(9092, 9093),
    val objectMapper: ObjectMapper = StoveObjectMapper.Default,
    val containerOptions: ContainerOptions = KafkaContainerOptions(),
    override val configureExposedConfiguration: (KafkaExposedConfiguration) -> List<String> = { _ -> listOf() }
) : SystemOptions, ConfiguresExposedConfiguration<KafkaExposedConfiguration>

data class KafkaContext(
    val container: KafkaContainer,
    val objectMapper: ObjectMapper,
    val configureExposedConfiguration: (KafkaExposedConfiguration) -> List<String>
)

fun TestSystem.withKafka(
    options: KafkaSystemOptions = KafkaSystemOptions()
): TestSystem = withProvidedRegistry(options.containerOptions.imageWithTag, registry = options.registry) {
    KafkaContainer(it).withExposedPorts(*options.ports.toTypedArray())
        .withEmbeddedZookeeper()
        .withReuse(this.options.keepDependenciesRunning)
}.let { getOrRegister(KafkaSystem(this, KafkaContext(it, options.objectMapper, options.configureExposedConfiguration))) }
    .let { this }

fun TestSystem.kafka(): KafkaSystem =
    getOrNone<KafkaSystem>().getOrElse { throw SystemNotRegisteredException(KafkaSystem::class) }

@ExperimentalStoveDsl
fun WithDsl.kafka(configure: () -> KafkaSystemOptions = { KafkaSystemOptions() }): TestSystem =
    this.testSystem.withKafka(configure())

suspend fun ValidationDsl.kafka(validation: suspend KafkaSystem.() -> Unit): Unit =
    validation(this.testSystem.kafka())
