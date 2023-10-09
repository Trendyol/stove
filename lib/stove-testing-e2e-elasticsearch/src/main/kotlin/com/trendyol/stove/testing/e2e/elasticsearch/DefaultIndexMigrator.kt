package com.trendyol.stove.testing.e2e.elasticsearch

import co.elastic.clients.elasticsearch.ElasticsearchClient
import co.elastic.clients.elasticsearch.indices.CreateIndexRequest
import com.trendyol.stove.testing.e2e.database.migrations.DatabaseMigration
import com.trendyol.stove.testing.e2e.database.migrations.MigrationPriority
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * Provides a default class for Elasticsearch when it is started successfully.
 * There is a default implementation [DefaultIndexMigrator] without any mapping. If you want to
 * modify the behaviour, you can implement [DatabaseMigration] and provide the instance to the [migrator] parameter. It will replace the default behaviour.
 */
data class DefaultIndex(
    val index: String,
    val migrator: DatabaseMigration<ElasticsearchClient> = DefaultIndexMigrator(index)
)

class DefaultIndexMigrator(private val index: String) : DatabaseMigration<ElasticsearchClient> {
    private val logger: Logger = LoggerFactory.getLogger(javaClass)

    override val order: Int = MigrationPriority.HIGHEST.value

    override suspend fun execute(connection: ElasticsearchClient) {
        val createIndexRequest: CreateIndexRequest =
            CreateIndexRequest.Builder()
                .index(index)
                .build()
        val response = connection.indices().create(createIndexRequest)
        if (!response.shardsAcknowledged()) {
            logger.info("Shards are not acknowledged for $index")
        }

        if (response.acknowledged()) {
            logger.info("$index is acknowledged")
        }

        logger.info("$index is created")
    }
}
