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
import com.trendyol.stove.testing.e2e.system.annotations.StoveDsl
import org.testcontainers.couchbase.BucketDefinition
import org.testcontainers.couchbase.CouchbaseContainer

data class CouchbaseExposedConfiguration(
    val connectionString: String,
    val hostsWithPort: String,
    val username: String,
    val password: String
) : ExposedConfiguration

@StoveDsl
data class CouchbaseSystemOptions(
    val defaultBucket: String,
    val containerOptions: ContainerOptions = ContainerOptions(),
    override val configureExposedConfiguration: (CouchbaseExposedConfiguration) -> List<String> = { _ -> listOf() },
    val objectMapper: ObjectMapper = StoveObjectMapper.byConfiguring { registerModule(JsonValueModule()) }
) : SystemOptions, ConfiguresExposedConfiguration<CouchbaseExposedConfiguration> {
    internal val migrationCollection: MigrationCollection<ReactiveCluster> = MigrationCollection()

    /**
     * Helps for registering migrations before the tests run.
     * @see MigrationCollection
     * @see DatabaseMigration
     */
    @StoveDsl
    fun migrations(migration: MigrationCollection<ReactiveCluster>.() -> Unit): CouchbaseSystemOptions =
        migration(
            migrationCollection
        ).let { this }
}

@StoveDsl
data class CouchbaseContext(
    val bucket: BucketDefinition,
    val container: CouchbaseContainer,
    val options: CouchbaseSystemOptions
)

@StoveDsl
data class ContainerOptions(
    val registry: String = DEFAULT_REGISTRY,
    val imageVersion: String = "latest"
)

internal fun TestSystem.withCouchbase(options: CouchbaseSystemOptions): TestSystem {
    val bucketDefinition = BucketDefinition(options.defaultBucket)
    val couchbaseContainer =
        withProvidedRegistry("couchbase/server:${options.containerOptions.imageVersion}", options.containerOptions.registry) {
            CouchbaseContainer(it).withBucket(bucketDefinition)
                .withReuse(this.options.keepDependenciesRunning)
        }
    this.getOrRegister(
        CouchbaseSystem(this, CouchbaseContext(bucketDefinition, couchbaseContainer, options))
    )
    return this
}

internal fun TestSystem.couchbase(): CouchbaseSystem =
    getOrNone<CouchbaseSystem>().getOrElse {
        throw SystemNotRegisteredException(CouchbaseSystem::class)
    }

@StoveDsl
fun WithDsl.couchbase(configure: () -> CouchbaseSystemOptions): TestSystem = this.testSystem.withCouchbase(configure())

@StoveDsl
suspend fun ValidationDsl.couchbase(validation: suspend CouchbaseSystem.() -> Unit): Unit = validation(this.testSystem.couchbase())
