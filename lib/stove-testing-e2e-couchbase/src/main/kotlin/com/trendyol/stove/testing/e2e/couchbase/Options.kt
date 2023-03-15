package com.trendyol.stove.testing.e2e.couchbase

import com.couchbase.client.java.ReactiveCluster
import com.couchbase.client.java.json.JsonValueModule
import com.fasterxml.jackson.databind.ObjectMapper
import com.trendyol.stove.testing.e2e.containers.DEFAULT_REGISTRY
import com.trendyol.stove.testing.e2e.database.migrations.DatabaseMigration
import com.trendyol.stove.testing.e2e.database.migrations.MigrationCollection
import com.trendyol.stove.testing.e2e.serialization.StoveObjectMapper
import com.trendyol.stove.testing.e2e.system.abstractions.ConfiguresExposedConfiguration
import com.trendyol.stove.testing.e2e.system.abstractions.ExposedConfiguration
import com.trendyol.stove.testing.e2e.system.abstractions.SystemOptions
import org.testcontainers.couchbase.BucketDefinition
import org.testcontainers.couchbase.CouchbaseContainer

data class CouchbaseExposedConfiguration(
    val connectionString: String,
    val hostsWithPort: String,
    val username: String,
    val password: String,
) : ExposedConfiguration

data class CouchbaseSystemOptions(
    val defaultBucket: String,
    val registry: String = DEFAULT_REGISTRY,
    override val configureExposedConfiguration: (CouchbaseExposedConfiguration) -> List<String> = { _ -> listOf() },
    val objectMapper: ObjectMapper = StoveObjectMapper.byConfiguring { registerModule(JsonValueModule()) },
) : SystemOptions, ConfiguresExposedConfiguration<CouchbaseExposedConfiguration> {

    internal val migrationCollection: MigrationCollection<ReactiveCluster> = MigrationCollection()

    /**
     * Helps for registering migrations before the tests run.
     * @see MigrationCollection
     * @see DatabaseMigration
     */
    fun migrations(migration: MigrationCollection<ReactiveCluster>.() -> Unit): CouchbaseSystemOptions = migration(
        migrationCollection
    ).let { this }
}

data class CouchbaseContext(
    val bucket: BucketDefinition,
    val container: CouchbaseContainer,
    val options: CouchbaseSystemOptions,
)
