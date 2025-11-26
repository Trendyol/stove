package com.trendyol.stove.testing.e2e.elasticsearch

import arrow.core.*
import com.fasterxml.jackson.databind.ObjectMapper
import com.trendyol.stove.testing.e2e.containers.*
import com.trendyol.stove.testing.e2e.database.migrations.*
import com.trendyol.stove.testing.e2e.serialization.StoveSerde
import com.trendyol.stove.testing.e2e.system.abstractions.*
import com.trendyol.stove.testing.e2e.system.annotations.StoveDsl
import io.ktor.client.*
import org.testcontainers.elasticsearch.ElasticsearchContainer
import org.testcontainers.utility.DockerImageName

@DslMarker
@Target(AnnotationTarget.CLASS, AnnotationTarget.TYPEALIAS, AnnotationTarget.TYPE, AnnotationTarget.FUNCTION)
annotation class ElasticDsl

/**
 * Options for configuring the Elasticsearch system in container mode.
 */
@StoveDsl
open class ElasticsearchSystemOptions(
  /**
   * Optional custom HTTP client configurer.
   * If not provided, a default Ktor HTTP client will be created.
   */
  open val httpClientConfigurer: Option<(cfg: ElasticSearchExposedConfiguration) -> HttpClient> = none(),
  open val container: ElasticContainerOptions = ElasticContainerOptions(),
  /**
   * Jackson ObjectMapper for JSON serialization/deserialization.
   */
  open val objectMapper: ObjectMapper = StoveSerde.jackson.default,
  /**
   * Cleanup function called when the system is closed.
   */
  open val cleanup: suspend (ElasticsearchSystem) -> Unit = {},
  override val configureExposedConfiguration: (ElasticSearchExposedConfiguration) -> List<String>
) : SystemOptions,
  ConfiguresExposedConfiguration<ElasticSearchExposedConfiguration>,
  SupportsMigrations<ElasticsearchSystem, ElasticsearchSystemOptions> {
  override val migrationCollection: MigrationCollection<ElasticsearchSystem> = MigrationCollection()

  companion object {
    /**
     * Creates options configured to use an externally provided Elasticsearch instance
     * instead of a testcontainer.
     *
     * @param host The Elasticsearch host
     * @param port The Elasticsearch port
     * @param password The Elasticsearch password (for authentication)
     * @param isSecure Whether to use HTTPS (default: false for provided instances)
     * @param httpClientConfigurer Optional custom HTTP client configurer
     * @param objectMapper Jackson ObjectMapper for serialization
     * @param runMigrations Whether to run migrations on the external instance (default: true)
     * @param cleanup A suspend function to clean up data after tests complete
     * @param configureExposedConfiguration Function to map exposed config to application properties
     */
    @StoveDsl
    fun provided(
      host: String,
      port: Int,
      password: String = "",
      isSecure: Boolean = false,
      httpClientConfigurer: Option<(cfg: ElasticSearchExposedConfiguration) -> HttpClient> = none(),
      objectMapper: ObjectMapper = StoveSerde.jackson.default,
      runMigrations: Boolean = true,
      cleanup: suspend (ElasticsearchSystem) -> Unit = {},
      configureExposedConfiguration: (ElasticSearchExposedConfiguration) -> List<String>
    ): ProvidedElasticsearchSystemOptions = ProvidedElasticsearchSystemOptions(
      config = ElasticSearchExposedConfiguration(
        host = host,
        port = port,
        password = password,
        isSecure = isSecure
      ),
      httpClientConfigurer = httpClientConfigurer,
      objectMapper = objectMapper,
      runMigrations = runMigrations,
      cleanup = cleanup,
      configureExposedConfiguration = configureExposedConfiguration
    )
  }
}

/**
 * Options for using an externally provided Elasticsearch instance.
 * This class holds the configuration for the external instance directly (non-nullable).
 */
@StoveDsl
class ProvidedElasticsearchSystemOptions(
  /**
   * The configuration for the provided Elasticsearch instance.
   */
  val config: ElasticSearchExposedConfiguration,
  httpClientConfigurer: Option<(cfg: ElasticSearchExposedConfiguration) -> HttpClient> = none(),
  objectMapper: ObjectMapper = StoveSerde.jackson.default,
  cleanup: suspend (ElasticsearchSystem) -> Unit = {},
  /**
   * Whether to run migrations on the external instance.
   */
  val runMigrations: Boolean = true,
  configureExposedConfiguration: (ElasticSearchExposedConfiguration) -> List<String>
) : ElasticsearchSystemOptions(
    httpClientConfigurer = httpClientConfigurer,
    container = ElasticContainerOptions(),
    objectMapper = objectMapper,
    cleanup = cleanup,
    configureExposedConfiguration = configureExposedConfiguration
  ),
  ProvidedSystemOptions<ElasticSearchExposedConfiguration> {
  override val providedConfig: ElasticSearchExposedConfiguration = config
  override val runMigrationsForProvided: Boolean = runMigrations
}

data class ElasticSearchExposedConfiguration(
  val host: String,
  val port: Int,
  val password: String,
  /**
   * Whether to use HTTPS for the connection.
   */
  val isSecure: Boolean = false
) : ExposedConfiguration

@StoveDsl
data class ElasticsearchContext(
  val runtime: SystemRuntime,
  val options: ElasticsearchSystemOptions
)

open class StoveElasticSearchContainer(
  override val imageNameAccess: DockerImageName
) : ElasticsearchContainer(imageNameAccess),
  StoveContainer

data class ElasticContainerOptions(
  override val registry: String = "docker.elastic.co/",
  override val tag: String = "8.6.1",
  override val image: String = "elasticsearch/elasticsearch",
  override val compatibleSubstitute: String? = null,
  val exposedPorts: List<Int> = listOf(DEFAULT_ELASTIC_PORT),
  val password: String = "password",
  val disableSecurity: Boolean = true,
  override val useContainerFn: UseContainerFn<StoveElasticSearchContainer> = { StoveElasticSearchContainer(it) },
  override val containerFn: ContainerFn<StoveElasticSearchContainer> = { }
) : ContainerOptions<StoveElasticSearchContainer> {
  companion object {
    const val DEFAULT_ELASTIC_PORT = 9200
  }
}
