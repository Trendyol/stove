package com.trendyol.stove.testing.e2e.elasticsearch

import arrow.core.Option
import arrow.core.none
import com.fasterxml.jackson.databind.ObjectMapper
import com.trendyol.stove.testing.e2e.containers.ExposedCertificate
import com.trendyol.stove.testing.e2e.serialization.StoveObjectMapper
import com.trendyol.stove.testing.e2e.system.abstractions.ConfiguresExposedConfiguration
import com.trendyol.stove.testing.e2e.system.abstractions.ExposedConfiguration
import com.trendyol.stove.testing.e2e.system.abstractions.SystemOptions
import org.apache.http.client.config.RequestConfig
import org.apache.http.impl.nio.client.HttpAsyncClientBuilder
import org.elasticsearch.client.RestClient
import org.testcontainers.elasticsearch.ElasticsearchContainer
import javax.net.ssl.SSLContext
import kotlin.time.Duration.Companion.minutes

data class ElasticsearchSystemOptions(
    val defaultIndex: DefaultIndex,
    val clientConfigurer: ElasticClientConfigurer = ElasticClientConfigurer(),
    val containerOptions: ContainerOptions = ContainerOptions(),
    val objectMapper: ObjectMapper = StoveObjectMapper.Default,
    override val configureExposedConfiguration: (ElasticSearchExposedConfiguration) -> List<String> = { _ -> listOf() },
) : SystemOptions, ConfiguresExposedConfiguration<ElasticSearchExposedConfiguration> {

    internal val migrationCollection: MigrationCollection = MigrationCollection()

    /**
     * Helps for registering migrations before the tests run.
     * @see MigrationCollection
     * @see ElasticMigrator
     */
    fun migrations(migration: MigrationCollection.() -> Unit): ElasticsearchSystemOptions = migration(migrationCollection).let { this }
}

data class ElasticsearchExposedCertificate(
    val bytes: ByteArray,
    val sslContext: SSLContext,
) : ExposedCertificate

data class ElasticSearchExposedConfiguration(
    val host: String,
    val port: Int,
    val password: String,
    val certificate: ExposedCertificate,
) : ExposedConfiguration

data class ElasticsearchContext(
    val index: String,
    val container: ElasticsearchContainer,
    val options: ElasticsearchSystemOptions,
)

data class ContainerOptions(
    val registry: String = "docker.elastic.co/",
    val imageVersion: String = "8.6.1",
    val exposedPorts: List<Int> = listOf(9200),
    val password: String = "password",
    val disableSecurity: Boolean = true,
    val configureContainer: ElasticsearchContainer.() -> Unit = {},
)

data class ElasticClientConfigurer(
    val httpClientBuilder: HttpAsyncClientBuilder.() -> Unit = {
        setDefaultRequestConfig(
            RequestConfig.custom()
                .setSocketTimeout(5.minutes.inWholeMilliseconds.toInt())
                .setConnectTimeout(5.minutes.inWholeMilliseconds.toInt())
                .setConnectionRequestTimeout(5.minutes.inWholeMilliseconds.toInt())
                .build()
        )
    },
    val restClientOverrideFn: Option<(cfg: ElasticSearchExposedConfiguration) -> RestClient> = none(),
)
