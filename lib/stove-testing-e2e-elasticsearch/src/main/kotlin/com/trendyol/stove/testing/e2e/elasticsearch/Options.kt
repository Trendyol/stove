package com.trendyol.stove.testing.e2e.elasticsearch

import arrow.core.*
import co.elastic.clients.elasticsearch.ElasticsearchClient
import co.elastic.clients.json.JsonpMapper
import co.elastic.clients.json.jackson.JacksonJsonpMapper
import com.trendyol.stove.testing.e2e.containers.*
import com.trendyol.stove.testing.e2e.database.migrations.*
import com.trendyol.stove.testing.e2e.serialization.StoveSerde
import com.trendyol.stove.testing.e2e.system.abstractions.*
import com.trendyol.stove.testing.e2e.system.annotations.StoveDsl
import org.apache.http.client.config.RequestConfig
import org.apache.http.impl.nio.client.HttpAsyncClientBuilder
import org.elasticsearch.client.RestClient
import org.testcontainers.elasticsearch.ElasticsearchContainer
import org.testcontainers.utility.DockerImageName
import kotlin.time.Duration.Companion.minutes

@DslMarker
@Target(AnnotationTarget.CLASS, AnnotationTarget.TYPEALIAS, AnnotationTarget.TYPE, AnnotationTarget.FUNCTION)
annotation class ElasticDsl

/**
 * Options for configuring the Elasticsearch system in container mode.
 */
@StoveDsl
open class ElasticsearchSystemOptions(
  open val clientConfigurer: ElasticClientConfigurer = ElasticClientConfigurer(),
  open val container: ElasticContainerOptions = ElasticContainerOptions(),
  open val jsonpMapper: JsonpMapper = JacksonJsonpMapper(StoveSerde.jackson.default),
  open val cleanup: suspend (ElasticsearchClient) -> Unit = {},
  override val configureExposedConfiguration: (ElasticSearchExposedConfiguration) -> List<String>
) : SystemOptions,
  ConfiguresExposedConfiguration<ElasticSearchExposedConfiguration>,
  SupportsMigrations<ElasticsearchClient, ElasticsearchSystemOptions> {
  override val migrationCollection: MigrationCollection<ElasticsearchClient> = MigrationCollection()

  companion object {
    /**
     * Creates options configured to use an externally provided Elasticsearch instance
     * instead of a testcontainer.
     *
     * @param host The Elasticsearch host
     * @param port The Elasticsearch port
     * @param password The Elasticsearch password (for authentication)
     * @param certificate Optional SSL certificate for secure connections
     * @param clientConfigurer Client configuration
     * @param jsonpMapper JSON mapper for serialization
     * @param runMigrations Whether to run migrations on the external instance (default: true)
     * @param cleanup A suspend function to clean up data after tests complete
     * @param configureExposedConfiguration Function to map exposed config to application properties
     */
    @StoveDsl
    fun provided(
      host: String,
      port: Int,
      password: String = "",
      certificate: ElasticsearchExposedCertificate? = null,
      clientConfigurer: ElasticClientConfigurer = ElasticClientConfigurer(),
      jsonpMapper: JsonpMapper = JacksonJsonpMapper(StoveSerde.jackson.default),
      runMigrations: Boolean = true,
      cleanup: suspend (ElasticsearchClient) -> Unit = {},
      configureExposedConfiguration: (ElasticSearchExposedConfiguration) -> List<String>
    ): ProvidedElasticsearchSystemOptions = ProvidedElasticsearchSystemOptions(
      config = ElasticSearchExposedConfiguration(
        host = host,
        port = port,
        password = password,
        certificate = certificate
      ),
      clientConfigurer = clientConfigurer,
      jsonpMapper = jsonpMapper,
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
  clientConfigurer: ElasticClientConfigurer = ElasticClientConfigurer(),
  jsonpMapper: JsonpMapper = JacksonJsonpMapper(StoveSerde.jackson.default),
  cleanup: suspend (ElasticsearchClient) -> Unit = {},
  /**
   * Whether to run migrations on the external instance.
   */
  val runMigrations: Boolean = true,
  configureExposedConfiguration: (ElasticSearchExposedConfiguration) -> List<String>
) : ElasticsearchSystemOptions(
    clientConfigurer = clientConfigurer,
    container = ElasticContainerOptions(),
    jsonpMapper = jsonpMapper,
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
  val certificate: ElasticsearchExposedCertificate?
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

data class ElasticClientConfigurer(
  val httpClientBuilder: HttpAsyncClientBuilder.() -> Unit = {
    setDefaultRequestConfig(
      RequestConfig
        .custom()
        .setSocketTimeout(5.minutes.inWholeMilliseconds.toInt())
        .setConnectTimeout(5.minutes.inWholeMilliseconds.toInt())
        .setConnectionRequestTimeout(5.minutes.inWholeMilliseconds.toInt())
        .build()
    )
  },
  val restClientOverrideFn: Option<(cfg: ElasticSearchExposedConfiguration) -> RestClient> = none()
)
