package com.trendyol.stove.kafka

import arrow.core.getOrElse
import com.trendyol.stove.containers.*
import com.trendyol.stove.database.migrations.*
import com.trendyol.stove.system.*
import com.trendyol.stove.system.abstractions.*
import com.trendyol.stove.system.annotations.StoveDsl
import org.apache.kafka.clients.admin.Admin
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.serialization.*
import org.springframework.kafka.core.KafkaTemplate
import org.testcontainers.kafka.ConfluentKafkaContainer
import org.testcontainers.utility.DockerImageName

open class StoveKafkaContainer(
  override val imageNameAccess: DockerImageName
) : ConfluentKafkaContainer(imageNameAccess),
  StoveContainer

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

/**
 * Operations for Kafka. It is used to customize the operations of Kafka.
 * The reason why this exists is to provide a way to interact with lower versions of Spring-Kafka dependencies.
 */
data class KafkaOps(
  val send: suspend (
    KafkaTemplate<*, *>,
    ProducerRecord<*, *>
  ) -> Unit
)

data class FallbackTemplateSerde(
  val keySerializer: Serializer<*> = StringSerializer(),
  val valueSerializer: Serializer<*> = StringSerializer()
)

/**
 * Context provided to Kafka migrations.
 * Contains the Admin client and options for performing setup operations.
 *
 * @property admin The Kafka Admin client for managing topics, ACLs, etc.
 * @property options The Kafka system options
 */
@StoveDsl
data class KafkaMigrationContext(
  val admin: Admin,
  val options: KafkaSystemOptions
)

/**
 * Options for configuring the Spring Kafka system in container mode.
 */
@StoveDsl
open class KafkaSystemOptions(
  /**
   * The registry of the Kafka image. The default value is `DEFAULT_REGISTRY`.
   */
  open val registry: String = DEFAULT_REGISTRY,
  /**
   * The ports of the Kafka container. The default value is `DEFAULT_KAFKA_PORTS`.
   */
  open val ports: List<Int> = DEFAULT_KAFKA_PORTS,
  /**
   * The fallback serde for Kafka. It is used to serialize and deserialize the messages before sending them to Kafka.
   * If no [KafkaTemplate] is provided, it will be used to create a new [KafkaTemplate].
   * Most of the time you won't need this.
   */
  open val fallbackSerde: FallbackTemplateSerde = FallbackTemplateSerde(),
  /**
   * Container options for Kafka.
   */
  open val containerOptions: KafkaContainerOptions = KafkaContainerOptions(),
  /**
   * Operations for Kafka. It is used to customize the operations of Kafka.
   * Defaults to [defaultKafkaOps] which works with Spring Kafka 2.x, 3.x, and 4.x.
   * @see KafkaOps
   * @see defaultKafkaOps
   */
  open val ops: KafkaOps = defaultKafkaOps(),
  /**
   * A suspend function to clean up data after tests complete.
   */
  open val cleanup: suspend (Admin) -> Unit = {},
  /**
   * The configuration of the Kafka settings that is exposed to the Application Under Test(AUT).
   */
  override val configureExposedConfiguration: (KafkaExposedConfiguration) -> List<String>
) : SystemOptions,
  ConfiguresExposedConfiguration<KafkaExposedConfiguration>,
  SupportsMigrations<KafkaMigrationContext, KafkaSystemOptions> {
  override val migrationCollection: MigrationCollection<KafkaMigrationContext> = MigrationCollection()

  companion object {
    val DEFAULT_KAFKA_PORTS = listOf(9092, 9093)

    /**
     * Creates options configured to use an externally provided Kafka instance
     * instead of a testcontainer.
     *
     * @param bootstrapServers The Kafka bootstrap servers (e.g., "localhost:9092")
     * @param registry The registry for the container (not used for provided instances)
     * @param ports The ports for the container (not used for provided instances)
     * @param fallbackSerde The fallback serde for serialization
     * @param ops Operations for Kafka
     * @param runMigrations Whether to run migrations on the external instance (default: true)
     * @param cleanup A suspend function to clean up data after tests complete
     * @param configureExposedConfiguration Function to map exposed config to application properties
     */
    @StoveDsl
    fun provided(
      bootstrapServers: String,
      registry: String = DEFAULT_REGISTRY,
      ports: List<Int> = DEFAULT_KAFKA_PORTS,
      fallbackSerde: FallbackTemplateSerde = FallbackTemplateSerde(),
      ops: KafkaOps = defaultKafkaOps(),
      runMigrations: Boolean = true,
      cleanup: suspend (Admin) -> Unit = {},
      configureExposedConfiguration: (KafkaExposedConfiguration) -> List<String>
    ): ProvidedKafkaSystemOptions = ProvidedKafkaSystemOptions(
      config = KafkaExposedConfiguration(bootstrapServers = bootstrapServers),
      registry = registry,
      ports = ports,
      fallbackSerde = fallbackSerde,
      ops = ops,
      runMigrations = runMigrations,
      cleanup = cleanup,
      configureExposedConfiguration = configureExposedConfiguration
    )
  }
}

/**
 * Options for using an externally provided Kafka instance.
 * This class holds the configuration for the external instance directly (non-nullable).
 */
@StoveDsl
class ProvidedKafkaSystemOptions(
  /**
   * The configuration for the provided Kafka instance.
   */
  val config: KafkaExposedConfiguration,
  registry: String = DEFAULT_REGISTRY,
  ports: List<Int> = DEFAULT_KAFKA_PORTS,
  fallbackSerde: FallbackTemplateSerde = FallbackTemplateSerde(),
  ops: KafkaOps = defaultKafkaOps(),
  cleanup: suspend (Admin) -> Unit = {},
  /**
   * Whether to run migrations on the external instance.
   */
  val runMigrations: Boolean = true,
  configureExposedConfiguration: (KafkaExposedConfiguration) -> List<String>
) : KafkaSystemOptions(
    registry = registry,
    ports = ports,
    fallbackSerde = fallbackSerde,
    containerOptions = KafkaContainerOptions(),
    ops = ops,
    cleanup = cleanup,
    configureExposedConfiguration = configureExposedConfiguration
  ),
  ProvidedSystemOptions<KafkaExposedConfiguration> {
  override val providedConfig: KafkaExposedConfiguration = config
  override val runMigrationsForProvided: Boolean = runMigrations
}

@StoveDsl
data class KafkaContext(
  val runtime: SystemRuntime,
  val options: KafkaSystemOptions
)

internal fun Stove.withKafka(
  options: KafkaSystemOptions,
  runtime: SystemRuntime
): Stove {
  getOrRegister(KafkaSystem(this, KafkaContext(runtime, options)))
  return this
}

internal fun Stove.kafka(): KafkaSystem = getOrNone<KafkaSystem>().getOrElse {
  throw SystemNotRegisteredException(KafkaSystem::class)
}

/**
 * Configures Spring Kafka system.
 *
 * For container-based setup:
 * ```kotlin
 * kafka {
 *   KafkaSystemOptions(
 *     cleanup = { admin -> admin.deleteTopics(...).all().get() },
 *     configureExposedConfiguration = { cfg -> listOf(...) }
 *   ).migrations {
 *     register<CreateTopicsMigration>()
 *   }
 * }
 * ```
 *
 * For provided (external) instance:
 * ```kotlin
 * kafka {
 *   KafkaSystemOptions.provided(
 *     bootstrapServers = "localhost:9092",
 *     runMigrations = true,
 *     cleanup = { admin -> admin.deleteTopics(...).all().get() },
 *     configureExposedConfiguration = { cfg -> listOf(...) }
 *   ).migrations {
 *     register<CreateTopicsMigration>()
 *   }
 * }
 * ```
 */
@StoveDsl
fun WithDsl.kafka(
  configure: () -> KafkaSystemOptions
): Stove {
  SpringKafkaVersionCheck.ensureSpringKafkaAvailable()
  val options = configure()

  val runtime: SystemRuntime = if (options is ProvidedKafkaSystemOptions) {
    ProvidedRuntime
  } else {
    withProvidedRegistry(
      options.containerOptions.imageWithTag,
      registry = options.registry,
      compatibleSubstitute = options.containerOptions.compatibleSubstitute
    ) {
      options.containerOptions
        .useContainerFn(it)
        .withExposedPorts(*options.ports.toTypedArray())
        .withReuse(stove.options.keepDependenciesRunning)
        .let { c -> c as StoveKafkaContainer }
        .apply(options.containerOptions.containerFn)
    }
  }

  return stove.withKafka(options, runtime)
}

@StoveDsl
suspend fun ValidationDsl.kafka(validation: suspend KafkaSystem.() -> Unit): Unit = validation(this.stove.kafka())
