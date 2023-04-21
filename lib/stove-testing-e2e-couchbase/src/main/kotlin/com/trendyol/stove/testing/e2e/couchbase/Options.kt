package com.trendyol.stove.testing.e2e.couchbase

import arrow.core.getOrElse
import com.couchbase.client.java.ReactiveCluster
import com.couchbase.client.java.json.JsonValueModule
import com.fasterxml.jackson.databind.ObjectMapper
import com.trendyol.stove.testing.e2e.containers.DEFAULT_REGISTRY
import com.trendyol.stove.testing.e2e.containers.withProvidedRegistry
import com.trendyol.stove.testing.e2e.database.migrations.DatabaseMigration
import com.trendyol.stove.testing.e2e.database.migrations.MigrationCollection
import com.trendyol.stove.testing.e2e.serialization.StoveObjectMapper
import com.trendyol.stove.testing.e2e.system.TestSystem
import com.trendyol.stove.testing.e2e.system.ValidationDsl
import com.trendyol.stove.testing.e2e.system.WithDsl
import com.trendyol.stove.testing.e2e.system.abstractions.*
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

fun TestSystem.withCouchbase(
    options: CouchbaseSystemOptions,
): TestSystem {
    val bucketDefinition = BucketDefinition(options.defaultBucket)
    val couchbaseContainer =
        withProvidedRegistry("couchbase/server", options.registry) {
            CouchbaseContainer(it).withBucket(bucketDefinition)
                .withReuse(this.options.keepDependenciesRunning)
        }
    this.getOrRegister(
        CouchbaseSystem(this, CouchbaseContext(bucketDefinition, couchbaseContainer, options))
    )
    return this
}

@ExperimentalDsl
fun WithDsl.couchbase(configure: () -> CouchbaseSystemOptions): TestSystem =
    this.testSystem.withCouchbase(configure())

fun TestSystem.couchbase(): CouchbaseSystem =
    getOrNone<CouchbaseSystem>().getOrElse {
        throw SystemNotRegisteredException(CouchbaseSystem::class)
    }

suspend fun ValidationDsl.couchbase(validation: suspend CouchbaseSystem.() -> Unit): Unit =
    validation(this.testSystem.couchbase())
