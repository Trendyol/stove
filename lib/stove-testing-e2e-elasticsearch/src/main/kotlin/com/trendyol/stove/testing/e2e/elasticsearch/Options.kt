package com.trendyol.stove.testing.e2e.elasticsearch

import arrow.core.None
import arrow.core.Option
import arrow.core.none
import co.elastic.clients.elasticsearch.ElasticsearchClient
import com.fasterxml.jackson.databind.ObjectMapper
import com.trendyol.stove.testing.e2e.database.migrations.DatabaseMigration
import com.trendyol.stove.testing.e2e.database.migrations.MigrationCollection
import com.trendyol.stove.testing.e2e.serialization.StoveObjectMapper
import com.trendyol.stove.testing.e2e.system.abstractions.ConfiguresExposedConfiguration
import com.trendyol.stove.testing.e2e.system.abstractions.ExposedConfiguration
import com.trendyol.stove.testing.e2e.system.abstractions.SystemOptions
import com.trendyol.stove.testing.e2e.system.annotations.StoveDsl
import org.apache.http.client.config.RequestConfig
import org.apache.http.impl.nio.client.HttpAsyncClientBuilder
import org.elasticsearch.client.RestClient
import org.testcontainers.elasticsearch.ElasticsearchContainer
import kotlin.time.Duration.Companion.minutes

@StoveDsl
data class ElasticsearchSystemOptions(
    val defaultIndex: DefaultIndex,
    val clientConfigurer: ElasticClientConfigurer = ElasticClientConfigurer(),
    val containerOptions: ContainerOptions = ContainerOptions(),
    val objectMapper: ObjectMapper = StoveObjectMapper.Default,
    override val configureExposedConfiguration: (ElasticSearchExposedConfiguration) -> List<String> = { _ -> listOf() }
) : SystemOptions, ConfiguresExposedConfiguration<ElasticSearchExposedConfiguration> {
    internal val migrationCollection: MigrationCollection<ElasticsearchClient> = MigrationCollection()

    /**
     * Helps for registering migrations before the tests run.
     * @see MigrationCollection
     * @see DatabaseMigration
     */
    fun migrations(migration: MigrationCollection<ElasticsearchClient>.() -> Unit): ElasticsearchSystemOptions =
        migration(
            migrationCollection
        ).let {
            this
        }
}

data class ElasticSearchExposedConfiguration(
    val host: String,
    val port: Int,
    val password: String,
    val certificate: Option<ElasticsearchExposedCertificate>
) : ExposedConfiguration

data class ElasticsearchContext(
    val index: String,
    val container: ElasticsearchContainer,
    val options: ElasticsearchSystemOptions
)

data class ContainerOptions(
    val registry: String = "docker.elastic.co/",
    val imageVersion: String = "8.6.1",
    val compatibleSubstitute: Option<String> = None,
    val exposedPorts: List<Int> = listOf(9200),
    val password: String = "password",
    val disableSecurity: Boolean = true,
    val configureContainer: ElasticsearchContainer.() -> Unit = {}
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
    val restClientOverrideFn: Option<(cfg: ElasticSearchExposedConfiguration) -> RestClient> = none()
)
