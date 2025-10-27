package com.trendyol.stove.example.java.spring.e2e.setup.migrations

import com.couchbase.client.kotlin.Cluster
import com.trendyol.stove.examples.java.spring.infra.components.product.persistency.CollectionConstants
import com.trendyol.stove.testing.e2e.database.migrations.DatabaseMigration
import kotlin.time.Duration.Companion.seconds

class CouchbaseMigration : DatabaseMigration<Cluster> {
  override val order: Int = 1

  override suspend fun execute(connection: Cluster) {
    val bucket = connection.bucket(CollectionConstants.BUCKET_NAME)
    listOf(CollectionConstants.PRODUCT_COLLECTION).forEach { collection ->
      bucket.collections.createCollection(bucket.defaultScope().name, collection)
    }
    connection.waitUntilReady(30.seconds)
  }
}
