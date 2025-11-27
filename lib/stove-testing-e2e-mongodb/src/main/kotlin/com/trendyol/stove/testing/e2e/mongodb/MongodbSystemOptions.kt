package com.trendyol.stove.testing.e2e.mongodb

import com.mongodb.MongoClientSettings
import com.mongodb.kotlin.client.coroutine.MongoClient
import com.trendyol.stove.testing.e2e.database.migrations.*
import com.trendyol.stove.testing.e2e.serialization.StoveSerde
import com.trendyol.stove.testing.e2e.system.abstractions.*
import com.trendyol.stove.testing.e2e.system.annotations.StoveDsl
import org.bson.json.JsonWriterSettings
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.MapperFeature
import tools.jackson.module.kotlin.KotlinModule

/**
 * Context provided to MongoDB migrations.
 * Contains the MongoDB client and options for performing setup operations.
 *
 * @property client The MongoDB client for executing operations
 * @property options The MongoDB system options
 */
@StoveDsl
data class MongodbMigrationContext(
  val client: MongoClient,
  val options: MongodbSystemOptions
)

/**
 * Options for configuring the MongoDB system in container mode.
 */
@StoveDsl
open class MongodbSystemOptions(
  open val databaseOptions: DatabaseOptions = DatabaseOptions(),
  open val container: MongoContainerOptions = MongoContainerOptions(),
  open val configureClient: (MongoClientSettings.Builder) -> Unit = { },
  open val serde: StoveSerde<Any, String> = StoveSerde.jackson.anyJsonStringSerde(
    StoveSerde.jackson.byConfiguring {
      disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
      enable(MapperFeature.DEFAULT_VIEW_INCLUSION)
      addModule(ObjectIdModule())
      addModule(KotlinModule.Builder().build())
    }
  ),
  open val jsonWriterSettings: JsonWriterSettings = StoveMongoJsonWriterSettings.objectIdAsString,
  open val cleanup: suspend (MongoClient) -> Unit = {},
  override val configureExposedConfiguration: (MongodbExposedConfiguration) -> List<String>
) : SystemOptions,
  ConfiguresExposedConfiguration<MongodbExposedConfiguration>,
  SupportsMigrations<MongodbMigrationContext, MongodbSystemOptions> {
  override val migrationCollection: MigrationCollection<MongodbMigrationContext> = MigrationCollection()

  companion object {
    /**
     * Creates options configured to use an externally provided MongoDB instance
     * instead of a testcontainer.
     *
     * @param connectionString The MongoDB connection string
     * @param host The MongoDB host
     * @param port The MongoDB port
     * @param replicaSetUrl The MongoDB replica set URL (defaults to connectionString)
     * @param databaseOptions Database options configuration
     * @param configureClient Client configuration function
     * @param serde Serialization/deserialization configuration
     * @param jsonWriterSettings JSON writer settings
     * @param runMigrations Whether to run migrations on the external instance (default: true)
     * @param cleanup A suspend function to clean up data after tests complete
     * @param configureExposedConfiguration Function to map exposed config to application properties
     */
    @StoveDsl
    fun provided(
      connectionString: String,
      host: String,
      port: Int,
      replicaSetUrl: String = connectionString,
      databaseOptions: DatabaseOptions = DatabaseOptions(),
      configureClient: (MongoClientSettings.Builder) -> Unit = { },
      serde: StoveSerde<Any, String> = StoveSerde.jackson.anyJsonStringSerde(
        StoveSerde.jackson.byConfiguring {
          disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
          enable(MapperFeature.DEFAULT_VIEW_INCLUSION)
          addModule(ObjectIdModule())
          addModule(KotlinModule.Builder().build())
        }
      ),
      jsonWriterSettings: JsonWriterSettings = StoveMongoJsonWriterSettings.objectIdAsString,
      runMigrations: Boolean = true,
      cleanup: suspend (MongoClient) -> Unit = {},
      configureExposedConfiguration: (MongodbExposedConfiguration) -> List<String>
    ): ProvidedMongodbSystemOptions = ProvidedMongodbSystemOptions(
      config = MongodbExposedConfiguration(
        connectionString = connectionString,
        host = host,
        port = port,
        replicaSetUrl = replicaSetUrl
      ),
      databaseOptions = databaseOptions,
      configureClient = configureClient,
      serde = serde,
      jsonWriterSettings = jsonWriterSettings,
      runMigrations = runMigrations,
      cleanup = cleanup,
      configureExposedConfiguration = configureExposedConfiguration
    )
  }
}

/**
 * Options for using an externally provided MongoDB instance.
 * This class holds the configuration for the external instance directly (non-nullable).
 */
@StoveDsl
class ProvidedMongodbSystemOptions(
  /**
   * The configuration for the provided MongoDB instance.
   */
  val config: MongodbExposedConfiguration,
  databaseOptions: DatabaseOptions = DatabaseOptions(),
  configureClient: (MongoClientSettings.Builder) -> Unit = { },
  serde: StoveSerde<Any, String> = StoveSerde.jackson.anyJsonStringSerde(
    StoveSerde.jackson.byConfiguring {
      disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
      enable(MapperFeature.DEFAULT_VIEW_INCLUSION)
      addModule(ObjectIdModule())
      addModule(KotlinModule.Builder().build())
    }
  ),
  jsonWriterSettings: JsonWriterSettings = StoveMongoJsonWriterSettings.objectIdAsString,
  /**
   * Whether to run migrations on the external instance.
   */
  val runMigrations: Boolean = true,
  cleanup: suspend (MongoClient) -> Unit = {},
  configureExposedConfiguration: (MongodbExposedConfiguration) -> List<String>
) : MongodbSystemOptions(
    databaseOptions = databaseOptions,
    container = MongoContainerOptions(),
    configureClient = configureClient,
    serde = serde,
    jsonWriterSettings = jsonWriterSettings,
    cleanup = cleanup,
    configureExposedConfiguration = configureExposedConfiguration
  ),
  ProvidedSystemOptions<MongodbExposedConfiguration> {
  override val providedConfig: MongodbExposedConfiguration = config
  override val runMigrationsForProvided: Boolean = runMigrations
}

object StoveMongoJsonWriterSettings {
  val objectIdAsString: JsonWriterSettings = JsonWriterSettings
    .builder()
    .objectIdConverter { value, writer -> writer.writeString(value.toHexString()) }
    .build()
}
