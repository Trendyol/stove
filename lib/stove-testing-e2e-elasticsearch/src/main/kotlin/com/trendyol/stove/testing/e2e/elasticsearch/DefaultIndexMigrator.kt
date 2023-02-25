package com.trendyol.stove.testing.e2e.elasticsearch

import co.elastic.clients.elasticsearch.ElasticsearchClient
import co.elastic.clients.elasticsearch.indices.CreateIndexRequest
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * Provides a default class for Elasticsearch when it is started successfully.
 * There is a default implementation [DefaultIndexMigrator] without any mapping. If you want to
 * modify the behaviour, you can implement [ElasticMigrator] and provide the instance to the [migrator] parameter. It will replace the default behaviour.
 */
data class DefaultIndex(
    val index: String,
    val migrator: ElasticMigrator = DefaultIndexMigrator(index),
)

class DefaultIndexMigrator(private val index: String) : ElasticMigrator {
    private val logger: Logger = LoggerFactory.getLogger(javaClass)
    override suspend fun migrate(client: ElasticsearchClient) {
        val createIndexRequest: CreateIndexRequest = CreateIndexRequest.Builder()
            .index(index)
            .build()
        val response = client.indices().create(createIndexRequest)
        if (!response.shardsAcknowledged()) {
            logger.info("Shards are not acknowledged for $index")
        }

        if (response.acknowledged()) {
            logger.info("$index is acknowledged")
        }

        logger.info("$index is created")
    }
}
