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
    override val containerFn: ContainerFn<KafkaContainer> = {}
) : ContainerOptions

data class KafkaOps(
    val send: suspend (
        KafkaTemplate<*, *>,
        ProducerRecord<*, *>
    ) -> Unit = { kafkaTemplate, record -> kafkaTemplate.sendCompatible(record) }
)

@StoveDsl
data class KafkaSystemOptions(
    val registry: String = DEFAULT_REGISTRY,
    val ports: List<Int> = listOf(9092, 9093),
    val objectMapper: ObjectMapper = StoveObjectMapper.Default,
    val containerOptions: KafkaContainerOptions = KafkaContainerOptions(),
    val ops: KafkaOps = KafkaOps(),
    override val configureExposedConfiguration: (KafkaExposedConfiguration) -> List<String> = { _ -> listOf() }
) : SystemOptions, ConfiguresExposedConfiguration<KafkaExposedConfiguration>

@StoveDsl
data class KafkaContext(
    val container: KafkaContainer,
    val objectMapper: ObjectMapper,
    val options: KafkaSystemOptions
)

internal fun TestSystem.withKafka(options: KafkaSystemOptions = KafkaSystemOptions()): TestSystem =
    withProvidedRegistry(
        options.containerOptions.imageWithTag,
        registry = options.registry,
        compatibleSubstitute = options.containerOptions.compatibleSubstitute
    ) {
        KafkaContainer(it)
            .withExposedPorts(*options.ports.toTypedArray())
            .apply { options.containerOptions.containerFn(this) }
            .withReuse(this.options.keepDependenciesRunning)
    }.let { getOrRegister(KafkaSystem(this, KafkaContext(it, options.objectMapper, options))) }
        .let { this }

internal fun TestSystem.kafka(): KafkaSystem = getOrNone<KafkaSystem>().getOrElse { throw SystemNotRegisteredException(KafkaSystem::class) }

@StoveDsl
fun WithDsl.kafka(configure: () -> KafkaSystemOptions = { KafkaSystemOptions() }): TestSystem = this.testSystem.withKafka(configure())

@StoveDsl
suspend fun ValidationDsl.kafka(validation: suspend KafkaSystem.() -> Unit): Unit = validation(this.testSystem.kafka())
