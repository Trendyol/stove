package com.trendyol.stove.testing.e2e.kafka

import arrow.core.getOrElse
import com.trendyol.stove.testing.e2e.containers.*
import com.trendyol.stove.testing.e2e.serialization.StoveSerde
import com.trendyol.stove.testing.e2e.system.*
import com.trendyol.stove.testing.e2e.system.abstractions.*
import com.trendyol.stove.testing.e2e.system.annotations.StoveDsl
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.serialization.*
import org.springframework.kafka.core.KafkaTemplate
import org.testcontainers.kafka.ConfluentKafkaContainer
import org.testcontainers.utility.DockerImageName

open class StoveKafkaContainer(
  override val imageNameAccess: DockerImageName
) : ConfluentKafkaContainer(imageNameAccess), StoveContainer

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

var stoveKafkaPublishSerdeRef: StoveSerde<Any, *> = StoveSerde.jackson.anyByteArraySerde()

data class FallbackTemplateSerde(
  val keySerializer: Serializer<*> = StringSerializer(),
  val valueSerializer: Serializer<*> = StringSerializer()
)

@StoveDsl
data class KafkaSystemOptions(

  /**
   * The registry of the Kafka image. The default value is `DEFAULT_REGISTRY`.
   */
  val registry: String = DEFAULT_REGISTRY,

  /**
   * The ports of the Kafka container. The default value is `DEFAULT_KAFKA_PORTS`.
   */
  val ports: List<Int> = DEFAULT_KAFKA_PORTS,

  /**
   * The serde that is used while publishing the messages. The default value is [StoveSerde.jackson].
   * You can also pass ser/de during the publish operation.
   * ```kotlin
   * kafka {
   *   publish("topic", "key", "value", serde = StoveSerde.jackson.stringSerde().some())
   * }
   * ```
   */
  val serdeForPublish: StoveSerde<Any, *> = StoveSerde.jackson.anyJsonStringSerde(),

  /**
   * The fallback serde for Kafka. It is used to serialize and deserialize the messages before sending them to Kafka.
   * If no [KafkaTemplate] is provided, it will be used to create a new [KafkaTemplate].
   * Most of the time you won't need this.
   */
  val fallbackSerde: FallbackTemplateSerde = FallbackTemplateSerde(),

  /**
   * Container options for Kafka.
   */
  val containerOptions: KafkaContainerOptions = KafkaContainerOptions(),

  /**
   * Operations for Kafka. It is used to customize the operations of Kafka.
   * The reason why this exists is to provide a way to interact with lower versions of Spring-Kafka dependencies.
   * @see KafkaOps
   */
  val ops: KafkaOps = KafkaOps(),

  /**
   * The configuration of the Kafka settings that is exposed to the Application Under Test(AUT).
   */
  override val configureExposedConfiguration: (KafkaExposedConfiguration) -> List<String>
) : SystemOptions, ConfiguresExposedConfiguration<KafkaExposedConfiguration> {
  companion object {
    val DEFAULT_KAFKA_PORTS = listOf(9092, 9093)
  }
}

@StoveDsl
data class KafkaContext(
  val container: StoveKafkaContainer,
  val options: KafkaSystemOptions
)

internal fun TestSystem.withKafka(options: KafkaSystemOptions): TestSystem =
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
  }.let { getOrRegister(KafkaSystem(this, KafkaContext(it, options))) }
    .let { this }

internal fun TestSystem.kafka(): KafkaSystem = getOrNone<KafkaSystem>().getOrElse {
  throw SystemNotRegisteredException(KafkaSystem::class)
}

@StoveDsl
fun WithDsl.kafka(
  configure: () -> KafkaSystemOptions
): TestSystem = this.testSystem.withKafka(configure())

@StoveDsl
suspend fun ValidationDsl.kafka(validation: suspend KafkaSystem.() -> Unit): Unit = validation(this.testSystem.kafka())
