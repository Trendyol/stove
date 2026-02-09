package com.trendyol.stove.kafka

import com.trendyol.stove.database.migrations.*
import com.trendyol.stove.kafka.intercepting.StoveKafkaBridge
import com.trendyol.stove.serialization.StoveSerde
import com.trendyol.stove.system.abstractions.*
import com.trendyol.stove.system.annotations.StoveDsl
import org.apache.kafka.clients.admin.Admin
import org.apache.kafka.common.serialization.Serializer

/**
 * Options for configuring the Kafka system in container or embedded mode.
 */
@StoveDsl
open class KafkaSystemOptions(
  /**
   * When set to `true`, an embedded Kafka broker is automatically started and used for the test run.
   * This is ideal for self-contained integration tests without external dependencies.
   * When `false`, the system will attempt to connect to a TestContainer Kafka instance.
   *
   * The default is `false`.
   */
  open val useEmbeddedKafka: Boolean = false,
  /**
   * Suffixes for error and retry topics in the application.
   */
  open val topicSuffixes: TopicSuffixes = TopicSuffixes(),
  /**
   * If true, the system will listen to the messages published by the Kafka system.
   */
  open val listenPublishedMessagesFromStove: Boolean = false,
  /**
   * The port of the bridge gRPC server that is used to communicate with the Kafka system.
   */
  open val bridgeGrpcServerPort: Int = stoveKafkaBridgePortDefault.toInt(),
  /**
   * The Serde that is used while asserting the messages,
   * serializing while bridging the messages.
   *
   * The default value is [StoveSerde.jackson]'s anyByteArraySerde.
   *
   * @see [com.trendyol.stove.kafka.intercepting.StoveKafkaBridge] for bridging the messages.
   * @see StoveKafkaValueSerializer for serializing the messages.
   * @see StoveKafkaValueDeserializer for deserializing the messages.
   */
  open val serde: StoveSerde<Any, ByteArray> = stoveSerdeRef,
  /**
   * The Value serializer that is used to serialize messages.
   */
  open val valueSerializer: Serializer<Any> = StoveKafkaValueSerializer(),
  /**
   * The options for the Kafka container.
   */
  open val containerOptions: KafkaContainerOptions = KafkaContainerOptions(),
  /**
   * A suspend function to clean up data after tests complete.
   */
  open val cleanup: suspend (Admin) -> Unit = {},
  /**
   * The options for the Kafka system that is exposed to the application.
   */
  override val configureExposedConfiguration: (KafkaExposedConfiguration) -> List<String>
) : SystemOptions,
  ConfiguresExposedConfiguration<KafkaExposedConfiguration>,
  SupportsMigrations<KafkaMigrationContext, KafkaSystemOptions> {
  override val migrationCollection: MigrationCollection<KafkaMigrationContext> = MigrationCollection()

  companion object {
    /**
     * Creates options configured to use an externally provided Kafka instance
     * instead of a testcontainer or embedded Kafka.
     *
     * @param bootstrapServers The Kafka bootstrap servers (e.g., "localhost:9092")
     * @param topicSuffixes Suffixes for error and retry topics
     * @param listenPublishedMessagesFromStove If true, the system will listen to published messages
     * @param bridgeGrpcServerPort The port of the bridge gRPC server
     * @param serde The Serde used for message serialization
     * @param valueSerializer The Value serializer for messages
     * @param runMigrations Whether to run migrations on the external instance (default: true)
     * @param cleanup A suspend function to clean up data after tests complete
     * @param configureExposedConfiguration Function to map exposed config to application properties
     */
    @StoveDsl
    fun provided(
      bootstrapServers: String,
      topicSuffixes: TopicSuffixes = TopicSuffixes(),
      listenPublishedMessagesFromStove: Boolean = false,
      bridgeGrpcServerPort: Int = stoveKafkaBridgePortDefault.toInt(),
      serde: StoveSerde<Any, ByteArray> = stoveSerdeRef,
      valueSerializer: Serializer<Any> = StoveKafkaValueSerializer(),
      runMigrations: Boolean = true,
      cleanup: suspend (Admin) -> Unit = {},
      configureExposedConfiguration: (KafkaExposedConfiguration) -> List<String>
    ): ProvidedKafkaSystemOptions = ProvidedKafkaSystemOptions(
      config = KafkaExposedConfiguration(
        bootstrapServers = bootstrapServers,
        interceptorClass = StoveKafkaBridge::class.java.name
      ),
      topicSuffixes = topicSuffixes,
      listenPublishedMessagesFromStove = listenPublishedMessagesFromStove,
      bridgeGrpcServerPort = bridgeGrpcServerPort,
      serde = serde,
      valueSerializer = valueSerializer,
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
  topicSuffixes: TopicSuffixes = TopicSuffixes(),
  listenPublishedMessagesFromStove: Boolean = false,
  bridgeGrpcServerPort: Int = stoveKafkaBridgePortDefault.toInt(),
  serde: StoveSerde<Any, ByteArray> = stoveSerdeRef,
  valueSerializer: Serializer<Any> = StoveKafkaValueSerializer(),
  cleanup: suspend (Admin) -> Unit = {},
  /**
   * Whether to run migrations on the external instance.
   */
  val runMigrations: Boolean = true,
  configureExposedConfiguration: (KafkaExposedConfiguration) -> List<String>
) : KafkaSystemOptions(
    useEmbeddedKafka = false,
    topicSuffixes = topicSuffixes,
    listenPublishedMessagesFromStove = listenPublishedMessagesFromStove,
    bridgeGrpcServerPort = bridgeGrpcServerPort,
    serde = serde,
    valueSerializer = valueSerializer,
    containerOptions = KafkaContainerOptions(),
    cleanup = cleanup,
    configureExposedConfiguration = configureExposedConfiguration
  ),
  ProvidedSystemOptions<KafkaExposedConfiguration> {
  override val providedConfig: KafkaExposedConfiguration = config
  override val runMigrationsForProvided: Boolean = runMigrations
}

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
 * Convenience type alias for Kafka migrations.
 *
 * Instead of writing `DatabaseMigration<KafkaMigrationContext>`, use `KafkaMigration`:
 * ```kotlin
 * class MyMigration : KafkaMigration {
 *   override val order: Int = 1
 *   override suspend fun execute(connection: KafkaMigrationContext) { ... }
 * }
 * ```
 */
typealias KafkaMigration = DatabaseMigration<KafkaMigrationContext>

/**
 * Suffixes for error and retry topics in the application.
 * Stove Kafka uses these suffixes to understand the intent of the topic and the message.
 */
data class TopicSuffixes(
  val error: List<String> = listOf(".error", ".DLT"),
  val retry: List<String> = listOf(".retry")
) {
  fun isRetryTopic(topic: String): Boolean = retry.any { topic.endsWith(it, ignoreCase = true) }

  fun isErrorTopic(topic: String): Boolean = error.any { topic.endsWith(it, ignoreCase = true) }
}
