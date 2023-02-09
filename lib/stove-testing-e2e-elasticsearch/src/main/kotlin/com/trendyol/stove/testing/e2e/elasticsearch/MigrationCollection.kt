package com.trendyol.stove.testing.e2e.elasticsearch

import co.elastic.clients.elasticsearch.ElasticsearchClient
import com.trendyol.stove.testing.e2e.system.abstractions.AfterRunAware
import kotlin.reflect.KClass
import kotlin.reflect.full.createInstance

/**
 * Interface for writing migrations, and operations that necessary for testing.
 * All the migrations will run after the Elastic instance run successfully.
 *
 * Migrations can be more than one.
 *
 * Migrations can not have **constructor dependencies.**
 * @see AfterRunAware.afterRun
 */
interface ElasticMigrator {

    /**
     * [client] is ready for executing operations
     */
    suspend fun migrate(client: ElasticsearchClient)
}

/**
 * The collection that stores the migrations of Elasticsearch for the application under test
 */
class MigrationCollection {
    private val types: MutableMap<KClass<*>, ElasticMigrator> = mutableMapOf()

    fun <T : ElasticMigrator> register(clazz: KClass<T>): MigrationCollection = types
        .putIfAbsent(clazz, clazz.createInstance() as ElasticMigrator)
        .let { this }

    fun <T : ElasticMigrator> register(
        clazz: KClass<T>,
        migrator: ElasticMigrator,
    ): MigrationCollection = types
        .put(clazz, migrator)
        .let { this }

    inline fun <reified T : ElasticMigrator> register(instance: () -> ElasticMigrator): MigrationCollection =
        this.register(T::class, instance()).let { this }

    fun <T : ElasticMigrator> replace(
        clazz: KClass<T>,
        migrator: ElasticMigrator,
    ): MigrationCollection = types
        .replace(clazz, migrator)
        .let { this }

    inline fun <reified T : ElasticMigrator> register(): MigrationCollection =
        this.register(T::class).let { this }

    inline fun <reified T : ElasticMigrator> replace(instance: () -> ElasticMigrator): MigrationCollection =
        this.replace(T::class, instance()).let { this }

    inline fun <reified TOld : ElasticMigrator, reified TNew : ElasticMigrator> replace(): MigrationCollection =
        this.replace(TOld::class, TNew::class.createInstance()).let { this }

    suspend fun run(esClient: ElasticsearchClient): Unit = types.map { it.value }.forEach { it.migrate(esClient) }
}
